package org.schabi.newpipe.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.settings.export.BackupFileLocator
import org.schabi.newpipe.settings.export.ImportExportManager
import org.schabi.newpipe.streams.io.StoredDirectoryHelper

/**
 * Periodically writes the same ZIP as the manual "export database" into the chosen folder.
 * It only ever creates new files; deleting old backups is left to the user.
 *
 * The idea (and the setting names, so that settings-only exports are interchangeable) comes from
 * PipePipe's scheduled backups.
 */
class ScheduledBackupWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!prefs.getBoolean(ctx.getString(R.string.scheduled_backup_key), false)) {
            return Result.success()
        }
        val path = prefs.getString(ctx.getString(R.string.scheduled_backup_path_key), null)
        if (path.isNullOrEmpty()) {
            return Result.success()
        }

        return try {
            val dir = StoredDirectoryHelper(ctx, Uri.parse(path), null)
            val name = "TubularData-auto-" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip"
            val file = dir.createUniqueFile(name, ZIP_MIME_TYPE)
                ?: throw IOException("Cannot create $name in $path")

            // getInstance() only builds the database; checkpoint() flushes the WAL into the file
            NewPipeDatabase.getInstance(ctx)
            NewPipeDatabase.checkpoint()
            ImportExportManager(BackupFileLocator(ctx)).exportDatabase(prefs, file)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled backup failed", e)
            ErrorUtil.createNotification(
                ctx,
                ErrorInfo(
                    e,
                    UserAction.DATABASE_IMPORT_EXPORT,
                    ctx.getString(R.string.scheduled_backup_failed)
                )
            )
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "ScheduledBackupWorker"
        private const val WORK_NAME = "scheduled_backup"
        private const val ZIP_MIME_TYPE = "application/zip"
        private const val DEFAULT_INTERVAL_SECONDS = 86400L

        /** Enqueues or cancels the periodic backup to match the current preferences. */
        @JvmStatic
        fun schedule(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val enabled = prefs.getBoolean(context.getString(R.string.scheduled_backup_key), false)
            val path = prefs.getString(context.getString(R.string.scheduled_backup_path_key), null)
            val workManager = WorkManager.getInstance(context)
            if (!enabled || path.isNullOrEmpty()) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val seconds = prefs.getString(
                context.getString(R.string.scheduled_backup_interval_key),
                context.getString(R.string.scheduled_backup_interval_default)
            )?.toLongOrNull() ?: DEFAULT_INTERVAL_SECONDS

            val request = PeriodicWorkRequest.Builder(
                ScheduledBackupWorker::class.java,
                seconds,
                TimeUnit.SECONDS
            )
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            // UPDATE keeps the running period, so calling this on every start is cheap
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
