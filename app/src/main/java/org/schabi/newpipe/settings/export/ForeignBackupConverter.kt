package org.schabi.newpipe.settings.export

import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.IOException
import org.schabi.newpipe.database.Migrations

/**
 * Recognises which app a backup came from and converts it as far as is safe, so it can be
 * imported through the normal import flow.
 *
 * - Databases: NewPipe, Tubular and Tubular Revived share the same schema. PipePipe
 *   (https://github.com/InfinityLoop1308/PipePipe) uses the custom version 901 and is converted.
 *   Older versions are migrated by Room itself. Anything newer or unknown is refused *before*
 *   it replaces the current database.
 * - Settings: keys Tubular knows are used as they are. Keys that only exist in another app are
 *   kept (so a later export contains them again) and remembered in [PREF_FOREIGN_KEYS], so they
 *   can be removed with [clearForeignPreferences].
 */
object ForeignBackupConverter {
    private const val TAG = "ForeignBackupConverter"

    /** Names of the settings that came from another app (a string set). */
    const val PREF_FOREIGN_KEYS = "foreign_pref_keys"

    /** Side table in the database that holds data of other apps that Tubular has no column for. */
    const val FOREIGN_EXTRA_TABLE = "foreign_backup_extra"

    private const val PIPEPIPE_DB_VERSION = 901
    private const val PIPEPIPE_MARKER_KEY = "last_used_pipepipe_preferences_version"
    private const val MIN_PIPEPIPE_KEYS = 5

    enum class BackupSource(val displayName: String) {
        NEWPIPE("NewPipe"),
        TUBULAR("Tubular"),
        REVIVED("Tubular Revived"),
        PIPEPIPE("PipePipe"),
        GENERIC("another app")
    }

    data class PrefsReport(val source: BackupSource, val foreignKeys: Int)

    private interface Converter {
        val source: BackupSource
        val dbVersion: Int
        val foreignKeys: Set<String>

        /** Settings that exist in both apps under a different key: other key -> Tubular key. */
        val renamedPrefs: Map<String, String>
        fun convert(db: SQLiteDatabase)
    }

    private object PipePipe : Converter {
        override val source = BackupSource.PIPEPIPE
        override val dbVersion = PIPEPIPE_DB_VERSION
        override val foreignKeys: Set<String> = ForeignKeys.PIPEPIPE
        override val renamedPrefs = emptyMap<String, String>()

        override fun convert(db: SQLiteDatabase) {
            // Values that have no place in Tubular's tables are kept in a side table instead of
            // being dropped (Room ignores tables it does not know). It travels with the database
            // file on every export and is removed by "clear data from other apps".
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `$FOREIGN_EXTRA_TABLE` (`source` TEXT NOT NULL, " +
                    "`tbl` TEXT NOT NULL, `row_id` INTEGER NOT NULL, `col` TEXT NOT NULL, " +
                    "`value` TEXT)"
            )
            db.execSQL(
                "INSERT INTO `$FOREIGN_EXTRA_TABLE` SELECT 'PipePipe', 'streams', uid, " +
                    "'is_paid', CAST(is_paid AS TEXT) FROM streams WHERE is_paid IS NOT NULL"
            )
            db.execSQL(
                "INSERT INTO `$FOREIGN_EXTRA_TABLE` SELECT 'PipePipe', 'playlists', uid, " +
                    "'thumbnail_url', thumbnail_url FROM playlists WHERE thumbnail_url IS NOT NULL"
            )

            // streams: PipePipe has an extra `is_paid` column which Room would reject.
            db.execSQL(
                "CREATE TABLE `streams_conv` (`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`service_id` INTEGER NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`stream_type` TEXT NOT NULL, `duration` INTEGER NOT NULL, " +
                    "`uploader` TEXT NOT NULL, `uploader_url` TEXT, `thumbnail_url` TEXT, " +
                    "`view_count` INTEGER, `textual_upload_date` TEXT, `upload_date` INTEGER, " +
                    "`is_upload_date_approximation` INTEGER)"
            )
            val streamCols = "uid, service_id, url, title, stream_type, duration, uploader, " +
                "uploader_url, thumbnail_url, view_count, textual_upload_date, upload_date, " +
                "is_upload_date_approximation"
            db.execSQL("INSERT INTO streams_conv ($streamCols) SELECT $streamCols FROM streams")
            db.execSQL("DROP TABLE streams")
            db.execSQL("ALTER TABLE streams_conv RENAME TO streams")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_streams_service_id_url` " +
                    "ON `streams` (`service_id`, `url`)"
            )

            // playlists: PipePipe stores a thumbnail url, Tubular a thumbnail stream id.
            // The first stream of each playlist becomes its (non-permanent) thumbnail.
            db.execSQL(
                "CREATE TABLE `playlists_conv` (`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT, `is_thumbnail_permanent` INTEGER NOT NULL, " +
                    "`thumbnail_stream_id` INTEGER NOT NULL, `display_index` INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO playlists_conv " +
                    "(uid, name, is_thumbnail_permanent, thumbnail_stream_id, display_index) " +
                    "SELECT p.uid, p.name, 0, COALESCE((SELECT j.stream_id " +
                    "FROM playlist_stream_join j WHERE j.playlist_id = p.uid " +
                    "ORDER BY j.join_index LIMIT 1), -1), p.display_index FROM playlists p"
            )
            db.execSQL("DROP TABLE playlists")
            db.execSQL("ALTER TABLE playlists_conv RENAME TO playlists")

            // remote_playlists: PipePipe has an additional index Room does not know about.
            db.execSQL("DROP INDEX IF EXISTS `index_remote_playlists_name`")

            // Let Room re-validate the schema and write its own identity hash on first open.
            db.execSQL("DROP TABLE IF EXISTS room_master_table")
        }
    }

    private val converters: List<Converter> = listOf(PipePipe)

    /**
     * Guesses the app a backup came from.
     *
     * @param dbVersion the backup database's version, or `null` for a settings-only backup
     * @param prefKeys the keys of the backup's settings
     */
    @JvmStatic
    fun detectSource(dbVersion: Int?, prefKeys: Set<String>): BackupSource {
        val pipePipeKeys = prefKeys.count { it in ForeignKeys.PIPEPIPE }
        val isPipePipe = dbVersion == PIPEPIPE_DB_VERSION ||
            PIPEPIPE_MARKER_KEY in prefKeys ||
            pipePipeKeys >= MIN_PIPEPIPE_KEYS
        val isRevived = "sponsor_block_proxy_url" in prefKeys ||
            "donation_reminder_next_at" in prefKeys ||
            "app_messages_seen_ids" in prefKeys
        val isNewPipeDb = dbVersion != null && dbVersion in 1..Migrations.DB_VER_9

        return when {
            isPipePipe -> BackupSource.PIPEPIPE
            isRevived -> BackupSource.REVIVED
            "sponsor_block_enable" in prefKeys -> BackupSource.TUBULAR
            isNewPipeDb || "last_used_preferences_version" in prefKeys -> BackupSource.NEWPIPE
            else -> BackupSource.GENERIC
        }
    }

    /**
     * Checks the extracted backup database at [dbFile] and converts it in place if it comes from
     * a supported fork. Call this on a temporary copy, before it replaces the real database.
     *
     * @return the source that was converted, or `null` if the file needed no conversion
     * @throws IOException if the database's version is not supported
     */
    @JvmStatic
    @Throws(IOException::class)
    fun prepareDatabase(dbFile: File): BackupSource? {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val version = db.version
            val converter = converters.firstOrNull { it.dbVersion == version }
            if (converter == null) {
                if (version !in 1..Migrations.DB_VER_9) {
                    throw IOException("Unsupported backup database version $version")
                }
                return null
            }

            Log.i(TAG, "Converting ${converter.source.displayName} database (v$version)")
            // table rebuilds must not cascade into the tables referencing them
            db.setForeignKeyConstraintsEnabled(false)
            db.beginTransaction()
            try {
                converter.convert(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.version = Migrations.DB_VER_9
            return converter.source
        } finally {
            db.close()
        }
    }

    /**
     * Looks at freshly imported [preferences], applies the key mapping of the app that produced
     * them and remembers which settings belong to another app.
     */
    @JvmStatic
    fun mapForeignPreferences(preferences: SharedPreferences): PrefsReport {
        val all = preferences.all
        val source = detectSource(null, all.keys)
        val converter = converters.firstOrNull { it.source == source }
            ?: return PrefsReport(source, 0)

        val editor = preferences.edit()
        for ((from, to) in converter.renamedPrefs) {
            val value = all[from] ?: continue
            editor.remove(from)
            when (value) {
                is Boolean -> editor.putBoolean(to, value)
                is Float -> editor.putFloat(to, value)
                is Int -> editor.putInt(to, value)
                is Long -> editor.putLong(to, value)
                is String -> editor.putString(to, value)
                is Set<*> -> editor.putStringSet(to, value.filterIsInstance<String>().toSet())
            }
        }

        val foreign = all.keys.filter { it in converter.foreignKeys }.toSet()
        if (foreign.isNotEmpty()) {
            val known = preferences.getStringSet(PREF_FOREIGN_KEYS, null) ?: emptySet()
            editor.putStringSet(PREF_FOREIGN_KEYS, known + foreign)
        }
        editor.commit()
        return PrefsReport(source, foreign.size)
    }

    /**
     * Drops the side table with data of other apps from the database at [dbFile] (the live
     * database; a second connection is fine, SQLite coordinates them).
     *
     * @return how many rows were removed
     */
    @JvmStatic
    fun clearForeignDatabaseData(dbFile: File): Int {
        if (!dbFile.exists()) return 0
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val exists = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                    arrayOf(FOREIGN_EXTRA_TABLE)
                ).use { it.moveToFirst() }
                if (!exists) return 0
                val rows = db.rawQuery("SELECT COUNT(*) FROM `$FOREIGN_EXTRA_TABLE`", null)
                    .use { if (it.moveToFirst()) it.getInt(0) else 0 }
                db.execSQL("DROP TABLE `$FOREIGN_EXTRA_TABLE`")
                rows
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear foreign database data", e)
            0
        }
    }

    /** Number of settings that came from other apps and can be cleared. */
    @JvmStatic
    fun foreignPreferenceCount(preferences: SharedPreferences): Int {
        return preferences.getStringSet(PREF_FOREIGN_KEYS, null)?.size ?: 0
    }

    /**
     * Removes the settings that came from other apps. Tubular's own settings are never touched:
     * only keys recorded at import time are removed.
     *
     * @return how many settings were recorded
     */
    @JvmStatic
    fun clearForeignPreferences(preferences: SharedPreferences): Int {
        val tracked = preferences.getStringSet(PREF_FOREIGN_KEYS, null) ?: return 0
        val editor = preferences.edit()
        tracked.forEach { editor.remove(it) }
        editor.remove(PREF_FOREIGN_KEYS)
        editor.commit()
        return tracked.size
    }
}
