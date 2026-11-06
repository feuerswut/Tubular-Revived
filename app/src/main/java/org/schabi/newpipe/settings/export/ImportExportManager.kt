package org.schabi.newpipe.settings.export

import android.content.SharedPreferences
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import com.grack.nanojson.JsonWriter
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipOutputStream
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.SharpOutputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ZipHelper

class ImportExportManager @JvmOverloads constructor(
    private val fileLocator: BackupFileLocator,
    /**
     * Checks (and, for backups of other apps, converts) an extracted backup database before it
     * replaces the real one. Replaceable so unit tests can run without Android's SQLite.
     */
    private val prepareDatabase: (File) -> ForeignBackupConverter.BackupSource? =
        ForeignBackupConverter::prepareDatabase
) {
    companion object {
        const val TAG = "ImportExportManager"
    }

    /**
     * Exports given [SharedPreferences] to the file in given outputPath.
     * It also creates the file.
     */
    @Throws(Exception::class)
    fun exportDatabase(preferences: SharedPreferences, file: StoredFileHelper) {
        // truncate the file before writing to it, otherwise if the new content is smaller than the
        // previous file size, the file will retain part of the previous content and be corrupted
        ZipOutputStream(SharpOutputStream(file.openAndTruncateStream()).buffered()).use { outZip ->
            // add the database
            val name = BackupFileLocator.FILE_NAME_DB
            ZipHelper.addFileToZip(outZip, name, fileLocator.db)

            // add the legacy vulnerable serialized preferences (will be removed in the future)
            ZipHelper.addFileToZip(
                outZip,
                BackupFileLocator.FILE_NAME_SERIALIZED_PREFS
            ) { byteOutput ->
                ObjectOutputStream(byteOutput).use { output ->
                    output.writeObject(preferences.all)
                    output.flush()
                }
            }

            // add the JSON preferences
            ZipHelper.addFileToZip(
                outZip,
                BackupFileLocator.FILE_NAME_JSON_PREFS
            ) { byteOutput ->
                JsonWriter
                    .indent("")
                    .on(byteOutput)
                    .`object`(preferences.all)
                    .done()
            }
        }
    }

    /**
     * Tries to create database directory if it does not exist.
     */
    @Throws(IOException::class)
    fun ensureDbDirectoryExists() {
        fileLocator.db.createParentDirectories()
    }

    /**
     * Extracts the database from the given file to the app's database directory.
     * The current app's database will be overwritten.
     * @param file the .zip file to extract the database from
     * @return true if the database was successfully extracted, false otherwise
     */
    @Throws(IOException::class)
    fun extractDb(file: StoredFileHelper): Boolean {
        val name = BackupFileLocator.FILE_NAME_DB
        // Extract next to the real database and only swap it in once it has been checked (and
        // converted, if it comes from another app). A backup that cannot be used therefore never
        // touches the current database.
        val temp = fileLocator.db.resolveSibling("$name.import")
        temp.deleteIfExists()
        lastConvertedFrom = null

        if (!ZipHelper.extractFileFromZip(file, name, temp)) {
            temp.deleteIfExists()
            return false
        }
        try {
            lastConvertedFrom = prepareDatabase(temp.toFile())
            Files.move(temp, fileLocator.db, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.deleteIfExists()
        }

        fileLocator.dbJournal.deleteIfExists()
        fileLocator.dbWal.deleteIfExists()
        fileLocator.dbShm.deleteIfExists()
        return true
    }

    /**
     * Removes everything that was imported from other apps and has no counterpart in Tubular:
     * settings that only exist there and the side table with their extra database values.
     * Tubular's own data and settings are not touched.
     * @return the number of removed settings and database rows
     */
    fun clearForeignData(preferences: SharedPreferences): Int {
        return ForeignBackupConverter.clearForeignPreferences(preferences) +
            ForeignBackupConverter.clearForeignDatabaseData(fileLocator.db.toFile())
    }

    /**
     * Exports only the settings, as JSON. Settings kept from other apps are included, so
     * exporting again does not lose them.
     */
    @Throws(Exception::class)
    fun exportSettingsOnly(preferences: SharedPreferences, file: StoredFileHelper) {
        SharpOutputStream(file.openAndTruncateStream()).buffered().use { out ->
            JsonWriter
                .indent("")
                .on(out)
                .`object`(preferences.all)
                .done()
        }
    }

    /**
     * Replaces all settings with the ones in the given JSON file (as written by
     * [exportSettingsOnly], or by the settings-only export of a NewPipe fork).
     */
    @Throws(IOException::class, JsonParserException::class)
    fun importSettingsOnly(file: StoredFileHelper, preferences: SharedPreferences) {
        val jsonObject = SharpInputStream(file.stream).buffered().use {
            JsonParser.`object`().from(it)
        }
        applyJsonPrefs(jsonObject, preferences)
    }

    /** The fork whose database the last [extractDb] had to convert, or null. */
    var lastConvertedFrom: ForeignBackupConverter.BackupSource? = null
        private set

    private fun applyJsonPrefs(jsonObject: JsonObject, preferences: SharedPreferences) {
        val editor = preferences.edit()
        editor.clear()

        for ((key, value) in jsonObject) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)

                is Float -> editor.putFloat(key, value)

                // nanojson reads fractional numbers as Double
                is Double -> editor.putFloat(key, value.toFloat())

                is Int -> editor.putInt(key, value)

                is Long -> editor.putLong(key, value)

                is String -> editor.putString(key, value)

                is JsonArray -> {
                    editor.putStringSet(key, value.mapNotNull { e -> e as? String }.toSet())
                }
            }
        }

        if (!editor.commit()) {
            throw IOException("Unable to commit imported preferences")
        }
    }

    @Deprecated(
        "Serializing preferences with Java's ObjectOutputStream is vulnerable to injections",
        replaceWith = ReplaceWith("exportHasJsonPrefs")
    )
    fun exportHasSerializedPrefs(zipFile: StoredFileHelper): Boolean {
        return ZipHelper.zipContainsFile(zipFile, BackupFileLocator.FILE_NAME_SERIALIZED_PREFS)
    }

    fun exportHasJsonPrefs(zipFile: StoredFileHelper): Boolean {
        return ZipHelper.zipContainsFile(zipFile, BackupFileLocator.FILE_NAME_JSON_PREFS)
    }

    /**
     * Remove all shared preferences from the app and load the preferences supplied to the manager.
     */
    @Deprecated(
        "Serializing preferences with Java's ObjectOutputStream is vulnerable to injections",
        replaceWith = ReplaceWith("loadJsonPrefs")
    )
    @Throws(IOException::class, ClassNotFoundException::class)
    fun loadSerializedPrefs(zipFile: StoredFileHelper, preferences: SharedPreferences) {
        ZipHelper.extractFileFromZip(zipFile, BackupFileLocator.FILE_NAME_SERIALIZED_PREFS) {
            PreferencesObjectInputStream(it).use { input ->
                @Suppress("UNCHECKED_CAST")
                val entries = input.readObject() as Map<String, *>

                val editor = preferences.edit()
                editor.clear()

                for ((key, value) in entries) {
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)

                        is Float -> editor.putFloat(key, value)

                        is Int -> editor.putInt(key, value)

                        is Long -> editor.putLong(key, value)

                        is String -> editor.putString(key, value)

                        is Set<*> -> {
                            // There are currently only Sets with type String possible
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, value as Set<String>?)
                        }
                    }
                }

                if (!editor.commit()) {
                    throw IOException("Unable to commit loadSerializedPrefs")
                }
            }
        }.let { fileExists ->
            if (!fileExists) {
                throw FileNotFoundException(BackupFileLocator.FILE_NAME_SERIALIZED_PREFS)
            }
        }
    }

    /**
     * Remove all shared preferences from the app and load the preferences supplied to the manager.
     */
    @Throws(IOException::class, JsonParserException::class)
    fun loadJsonPrefs(zipFile: StoredFileHelper, preferences: SharedPreferences) {
        ZipHelper.extractFileFromZip(zipFile, BackupFileLocator.FILE_NAME_JSON_PREFS) {
            applyJsonPrefs(JsonParser.`object`().from(it), preferences)
        }.let { fileExists ->
            if (!fileExists) {
                throw FileNotFoundException(BackupFileLocator.FILE_NAME_JSON_PREFS)
            }
        }
    }
}
