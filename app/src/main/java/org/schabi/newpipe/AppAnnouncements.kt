package org.schabi.newpipe

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.security.MessageDigest
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Dialogs shown when the app starts: a warning for builds that were not signed with the official
 * release key, and a recurring donation reminder.
 */
object AppAnnouncements {
    /** SHA-256 of the official release signing certificate (colon-free, upper case). */
    private const val OFFICIAL_CERT_SHA256 =
        "A78C8A685B02CD99BDBC5F18DD4B6AFA318C83036FB630CD6E7BE4DB801418FB"

    private const val PREF_DONATION_NEXT_AT = "donation_reminder_next_at"
    private const val DONATION_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
    private const val NEVER = Long.MAX_VALUE

    /** Called once per cold start of the main activity. */
    @JvmStatic
    fun onAppStart(activity: Activity) {
        if (activity.isFinishing) return
        // never stack two dialogs: the integrity warning wins
        if (showIntegrityWarningIfNeeded(activity)) return
        showDonationReminderIfDue(activity)
    }

    // ------------------------------------------------------------------ build integrity

    /** The certificate fingerprints this APK is signed with. */
    @Suppress("DEPRECATION")
    private fun signingCertSha256s(context: Context): Set<String> {
        val pm = context.packageManager
        val signatures: Array<android.content.pm.Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val s = info.signingInfo
                if (s == null) {
                    null
                } else if (s.hasMultipleSigners()) {
                    s.apkContentsSigners
                } else {
                    s.signingCertificateHistory
                }
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
        if (signatures == null) return emptySet()

        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            digest.digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
        }.toSet()
    }

    /** True if the app is a debug build or carries the official signature. */
    @JvmStatic
    fun isOfficialBuild(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        return try {
            signingCertSha256s(context).contains(OFFICIAL_CERT_SHA256)
        } catch (_: Exception) {
            false
        }
    }

    private fun showIntegrityWarningIfNeeded(activity: Activity): Boolean {
        if (isOfficialBuild(activity)) return false
        AlertDialog.Builder(activity)
            .setTitle(R.string.unofficial_build_title)
            .setMessage(R.string.unofficial_build_message)
            .setPositiveButton(R.string.unofficial_build_open_releases) { _, _ ->
                ShareUtils.openUrlInBrowser(
                    activity,
                    activity.getString(R.string.github_url) + "/releases"
                )
            }
            .setNegativeButton(R.string.unofficial_build_continue, null)
            .show()
        return true
    }

    // ------------------------------------------------------------------ donation reminder

    private fun donationLinks(context: Context): List<AppMessages.DonationLink> {
        val remote = AppMessages.storedDonationLinks(context)
        if (remote.isNotEmpty()) return remote
        val builtIn = context.getString(R.string.tubular_donation_url).trim()
        return if (AppMessages.isSafeUrl(builtIn)) {
            listOf(AppMessages.DonationLink(context.getString(R.string.donation_title), builtIn))
        } else {
            emptyList()
        }
    }

    private fun showDonationReminderIfDue(activity: Activity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val now = System.currentTimeMillis()
        val nextAt = prefs.getLong(PREF_DONATION_NEXT_AT, 0L)

        if (nextAt == 0L) {
            // first run: start the clock, don't greet new users with a donation request
            prefs.edit { putLong(PREF_DONATION_NEXT_AT, now + DONATION_INTERVAL_MS) }
            return
        }
        if (now < nextAt) return

        val links = donationLinks(activity)
        if (links.isEmpty()) return // nothing to point to; check again next start

        fun snooze() = prefs.edit { putLong(PREF_DONATION_NEXT_AT, now + DONATION_INTERVAL_MS) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.donation_reminder_title)
            .setNegativeButton(R.string.donation_reminder_later) { _, _ -> snooze() }
            .setNeutralButton(R.string.donation_reminder_never) { _, _ ->
                prefs.edit { putLong(PREF_DONATION_NEXT_AT, NEVER) }
            }
            .setOnCancelListener { snooze() }

        if (links.size == 1) {
            dialog.setMessage(R.string.donation_reminder_message)
                .setPositiveButton(R.string.donation_title) { _, _ ->
                    snooze()
                    ShareUtils.openUrlInBrowser(activity, links[0].url)
                }
        } else {
            dialog.setItems(links.map { it.label }.toTypedArray()) { _, which ->
                snooze()
                ShareUtils.openUrlInBrowser(activity, links[which].url)
            }
        }
        dialog.show()
    }
}
