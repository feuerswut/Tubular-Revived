package org.schabi.newpipe.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.settings.export.ForeignBackupConverter
import org.schabi.newpipe.settings.export.ForeignBackupConverter.BackupSource
import org.schabi.newpipe.settings.export.ForeignKeys

class ForeignBackupConverterTest {
    private fun detect(dbVersion: Int?, vararg keys: String): BackupSource {
        return ForeignBackupConverter.detectSource(dbVersion, keys.toSet())
    }

    @Test
    fun `PipePipe is recognised by its database version`() {
        assertEquals(BackupSource.PIPEPIPE, detect(901))
    }

    @Test
    fun `PipePipe is recognised by its marker setting or by several of its settings`() {
        assertEquals(BackupSource.PIPEPIPE, detect(null, "last_used_pipepipe_preferences_version"))
        val manyKeys = ForeignKeys.PIPEPIPE.take(5).toTypedArray()
        assertEquals(BackupSource.PIPEPIPE, detect(null, *manyKeys))
        // a single foreign key is not enough to call a backup PipePipe's
        assertEquals(BackupSource.GENERIC, detect(null, ForeignKeys.PIPEPIPE.first()))
    }

    @Test
    fun `Tubular Revived Tubular and NewPipe are told apart by their settings`() {
        assertEquals(BackupSource.REVIVED, detect(9, "sponsor_block_enable", "sponsor_block_proxy_url"))
        assertEquals(BackupSource.TUBULAR, detect(9, "sponsor_block_enable"))
        assertEquals(BackupSource.NEWPIPE, detect(9, "some_newpipe_setting"))
        assertEquals(BackupSource.NEWPIPE, detect(null, "last_used_preferences_version"))
    }

    @Test
    fun `Unknown backups are generic`() {
        assertEquals(BackupSource.GENERIC, detect(null))
        assertEquals(BackupSource.GENERIC, detect(null, "something_else"))
    }

    @Test
    fun `Settings Tubular uses are never listed as foreign`() {
        // these names are shared with PipePipe on purpose (scheduled backup, settings-only export)
        for (shared in listOf("scheduled_backup", "scheduled_backup_path", "scheduled_backup_interval")) {
            assertFalse(shared, shared in ForeignKeys.PIPEPIPE)
        }
        assertTrue(ForeignKeys.PIPEPIPE.isNotEmpty())
    }
}
