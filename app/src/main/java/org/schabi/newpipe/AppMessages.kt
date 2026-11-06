package org.schabi.newpipe

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import java.time.Instant
import org.schabi.newpipe.util.Version

/**
 * Messages the maintainer can push to active installations. They ride along with the regular
 * version check ([NewVersionWorker]): the version JSON may contain
 *
 * ```
 * "messages": [{
 *     "id": "2026-11-outage",          // required, unique; each id is shown at most once
 *     "title": "...", "text": "...",   // required
 *     "url": "https://...",            // optional, https only; opened when tapped
 *     "minVersion": "0.29.0",          // optional, inclusive
 *     "maxVersion": "0.29.2",          // optional, inclusive
 *     "expires": "2026-12-31T00:00:00Z" // optional, ISO-8601 instant
 * }],
 * "donations": [{ "label": "Ko-fi", "url": "https://..." }]
 * ```
 */
object AppMessages {
    private const val TAG = "AppMessages"
    private const val PREF_SEEN_IDS = "app_messages_seen_ids"
    const val PREF_DONATION_LINKS = "app_donation_links_json"
    private const val MAX_SEEN_IDS = 200
    private const val MAX_TEXT = 1000

    data class Message(
        val id: String,
        val title: String,
        val text: String,
        val url: String?,
        val minVersion: String?,
        val maxVersion: String?,
        val expiresAt: Long?
    )

    data class DonationLink(val label: String, val url: String)

    @JvmStatic
    fun isSafeUrl(url: String?): Boolean =
        url != null && url.startsWith("https://") && url.length <= 2048

    fun parseMessages(array: JsonArray?): List<Message> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.getString("id", "").trim()
            val title = o.getString("title", "").trim()
            val text = o.getString("text", "").trim()
            if (id.isEmpty() || id.length > 100 || title.isEmpty() || text.isEmpty()) {
                return@mapNotNull null
            }
            val url = o.getString("url", "").trim().takeIf { isSafeUrl(it) }
            val expires = o.getString("expires", "").takeIf { it.isNotBlank() }?.let {
                try {
                    Instant.parse(it).toEpochMilli()
                } catch (_: Exception) {
                    // an unparsable expiry must not make a message live forever
                    return@mapNotNull null
                }
            }
            Message(
                id,
                title.take(MAX_TEXT),
                text.take(MAX_TEXT),
                url,
                o.getString("minVersion", "").takeIf { it.isNotBlank() },
                o.getString("maxVersion", "").takeIf { it.isNotBlank() },
                expires
            )
        }
    }

    fun isApplicable(message: Message, ourVersion: Version, nowMillis: Long): Boolean {
        if (message.expiresAt != null && nowMillis >= message.expiresAt) return false
        if (message.minVersion != null && ourVersion < Version.fromString(message.minVersion)) {
            return false
        }
        if (message.maxVersion != null && ourVersion > Version.fromString(message.maxVersion)) {
            return false
        }
        return true
    }

    fun parseDonationLinks(array: JsonArray?): List<DonationLink> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val label = o.getString("label", "").trim()
            val url = o.getString("url", "").trim()
            if (label.isEmpty() || !isSafeUrl(url)) null else DonationLink(label.take(60), url)
        }
    }

    /** Donation links last received from the server, or an empty list. */
    @JvmStatic
    fun storedDonationLinks(context: Context): List<DonationLink> {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_DONATION_LINKS, null) ?: return emptyList()
        return try {
            parseDonationLinks(com.grack.nanojson.JsonParser.array().from(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Handles the `messages` and `donations` parts of a version-check response. */
    fun process(context: Context, json: JsonObject) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val donations = parseDonationLinks(json.getArray("donations"))
        prefs.edit().putString(
            PREF_DONATION_LINKS,
            JsonWriter.string(
                donations.map { mapOf("label" to it.label, "url" to it.url) }
            )
        ).apply()

        val seen = prefs.getString(PREF_SEEN_IDS, "")!!
            .split('\n').filter { it.isNotEmpty() }.toMutableList()
        val ourVersion = Version.fromString(BuildConfig.VERSION_NAME)
        val now = System.currentTimeMillis()

        for (message in parseMessages(json.getArray("messages"))) {
            if (message.id in seen || !isApplicable(message, ourVersion, now)) continue
            if (show(context, message)) {
                seen.add(message.id)
            }
        }
        prefs.edit()
            .putString(PREF_SEEN_IDS, seen.takeLast(MAX_SEEN_IDS).joinToString("\n"))
            .apply()
    }

    /** @return true if the message counts as delivered (shown, or nobody could ever see it) */
    private fun show(context: Context, message: Message): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            // keep it pending: the user may enable notifications later
            return false
        }
        val builder = NotificationCompat.Builder(
            context,
            context.getString(R.string.app_update_notification_channel_id)
        )
            .setSmallIcon(R.drawable.ic_tubular_update)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))

        if (message.url != null) {
            val intent = Intent(Intent.ACTION_VIEW, message.url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentIntent(
                PendingIntentCompat.getActivity(context, 0, intent, 0, false)
            )
        }
        return try {
            manager.notify(NOTIFICATION_ID_BASE + (message.id.hashCode() and 0xFFF), builder.build())
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not show message", e)
            false
        }
    }

    private const val NOTIFICATION_ID_BASE = 3000
}
