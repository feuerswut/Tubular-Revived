package org.schabi.newpipe

import com.grack.nanojson.JsonParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.util.Version

class AppMessagesTest {
    private fun parse(json: String) = AppMessages.parseMessages(JsonParser.array().from(json))
    private val now = Instant.parse("2026-11-06T00:00:00Z").toEpochMilli()
    private val v = Version.fromString("0.29.2")

    @Test
    fun `incomplete messages are dropped`() {
        val m = parse(
            """[{"id":"a","title":"t"},{"title":"t","text":"x"},{"id":"b","title":"t","text":"x"}]"""
        )
        assertEquals(listOf("b"), m.map { it.id })
    }

    @Test
    fun `only https urls are kept`() {
        val m = parse(
            """[{"id":"a","title":"t","text":"x","url":"http://e.com"},
                {"id":"b","title":"t","text":"x","url":"javascript:alert(1)"},
                {"id":"c","title":"t","text":"x","url":"https://e.com"}]"""
        )
        assertNull(m[0].url)
        assertNull(m[1].url)
        assertEquals("https://e.com", m[2].url)
    }

    @Test
    fun `invalid expiry drops the message instead of making it eternal`() {
        assertTrue(parse("""[{"id":"a","title":"t","text":"x","expires":"tomorrow"}]""").isEmpty())
    }

    @Test
    fun `version range and expiry are honoured`() {
        val m = parse(
            """[{"id":"old","title":"t","text":"x","maxVersion":"0.29.1"},
                {"id":"new","title":"t","text":"x","minVersion":"0.29.2"},
                {"id":"gone","title":"t","text":"x","expires":"2026-11-01T00:00:00Z"},
                {"id":"live","title":"t","text":"x","expires":"2026-12-01T00:00:00Z"}]"""
        ).associateBy { it.id }
        assertFalse(AppMessages.isApplicable(m.getValue("old"), v, now))
        assertTrue(AppMessages.isApplicable(m.getValue("new"), v, now))
        assertFalse(AppMessages.isApplicable(m.getValue("gone"), v, now))
        assertTrue(AppMessages.isApplicable(m.getValue("live"), v, now))
    }

    @Test
    fun `donation links need a label and an https url`() {
        val links = AppMessages.parseDonationLinks(
            JsonParser.array().from(
                """[{"label":"A","url":"https://a.example"},{"label":"","url":"https://b.example"},
                    {"label":"C","url":"http://c.example"}]"""
            )
        )
        assertEquals(listOf("A"), links.map { it.label })
    }
}
