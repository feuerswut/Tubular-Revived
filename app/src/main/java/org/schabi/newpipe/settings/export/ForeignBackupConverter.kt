package org.schabi.newpipe.settings.export

import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import org.schabi.newpipe.database.Migrations

/**
 * Converts backups made by NewPipe forks into the format Tubular expects, so they can be imported
 * through the normal "import database" flow. Currently supported: PipePipe
 * (https://github.com/InfinityLoop1308/PipePipe), whose database uses the custom version 901.
 *
 * The conversion is a generalized mapping: every foreign source is described by its database
 * version plus a list of table rebuilds ([Source.convert]) and a preference mapping
 * ([Source.renamedPrefs], [Source.droppedPrefs]).
 */
object ForeignBackupConverter {
    private const val TAG = "ForeignBackupConverter"

    private interface Source {
        val name: String
        val dbVersion: Int
        val renamedPrefs: Map<String, String>
        val droppedPrefs: Set<String>
        fun convert(db: SQLiteDatabase)
    }

    private object PipePipe : Source {
        override val name = "PipePipe"
        override val dbVersion = 901

        /** PipePipe key -> Tubular key. Add entries here when a setting was merely renamed. */
        override val renamedPrefs = emptyMap<String, String>()

        /** PipePipe-only settings that mean nothing (or are unsafe) in Tubular. */
        override val droppedPrefs = setOf(
            "last_used_pipepipe_preferences_version"
        )

        override fun convert(db: SQLiteDatabase) {
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

    private val sources: List<Source> = listOf(PipePipe)

    /**
     * Converts the database at [dbFile] in place if it comes from a supported fork.
     *
     * @return the name of the fork that was converted, or `null` if the file needed no conversion
     */
    @JvmStatic
    fun convertDatabaseIfForeign(dbFile: File): String? {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val source = sources.firstOrNull { it.dbVersion == db.version } ?: return null
            Log.i(TAG, "Converting ${source.name} database (v${db.version})")

            // table rebuilds must not cascade into the tables referencing them
            db.setForeignKeyConstraintsEnabled(false)
            db.beginTransaction()
            try {
                source.convert(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.version = Migrations.DB_VER_9
            return source.name
        } finally {
            db.close()
        }
    }

    /**
     * Applies the key mapping of the fork that produced the backup to freshly imported
     * [preferences]. Detection is done by the fork's marker preference.
     *
     * @return `true` if a mapping was applied
     */
    @JvmStatic
    fun mapForeignPreferences(preferences: SharedPreferences): Boolean {
        val source = sources.firstOrNull { s -> s.droppedPrefs.any { preferences.contains(it) } }
            ?: return false

        val editor = preferences.edit()
        source.droppedPrefs.forEach { editor.remove(it) }
        for ((from, to) in source.renamedPrefs) {
            val value = preferences.all[from] ?: continue
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
        editor.apply()
        return true
    }
}
