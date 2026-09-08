package com.nightguard.app.util

import android.content.Context
import android.util.Base64
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import com.nightguard.app.data.db.TimelineEvent
import java.io.File
import java.util.Date

/**
 * Builds a single self-contained HTML file (photos embedded as base64 data URIs, so it
 * has no external dependencies) summarizing the timeline over a date range -- meant to be
 * shared with or printed for a clinician. Voice memos are noted but not embedded (play
 * them back in the app instead); embedding audio as a data URI bloats the file a lot for
 * comparatively little benefit here.
 */
object ReportExporter {

    suspend fun generateReport(context: Context, startMillis: Long, endMillis: Long): File {
        val repo = TimelineRepository(context)
        val events = repo.eventsBetween(startMillis, endMillis)
        val imageStore = SecureImageStore(context)
        val dateFormat = TimeFormat.dateTime()

        val html = buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<title>NightGuard report</title>")
            append(
                "<style>" +
                    "body{font-family:sans-serif;max-width:800px;margin:24px auto;padding:0 16px;color:#111}" +
                    "h1{font-size:20px}" +
                    ".event{border-bottom:1px solid #ddd;padding:12px 0}" +
                    ".time{color:#555;font-size:13px}" +
                    ".type{font-weight:bold}" +
                    ".event.incognito{background:#fff3e0}" +
                    ".event.episode{background:#e3f2fd;border-left:4px solid #1565c0;padding-left:12px}" +
                    ".badge{display:inline-block;background:#e65100;color:#fff;font-size:11px;padding:1px 6px;border-radius:8px;margin-left:6px}" +
                    ".badge.episode{background:#1565c0}" +
                    "img{max-width:100%;margin-top:8px;border-radius:4px}" +
                    "</style>"
            )
            append("</head><body>")
            append("<h1>NightGuard report</h1>")
            append(
                "<p>Range: ${dateFormat.format(Date(startMillis))} &ndash; ${dateFormat.format(Date(endMillis))}" +
                    "<br>Generated: ${dateFormat.format(Date())}<br>${events.size} events</p>"
            )
            for (event in events) {
                val isEpisodeMarker = event.type == EventType.EPISODE_MARKER
                val cssClass = if (event.isIncognito) " incognito" else if (isEpisodeMarker) " episode" else ""
                append("<div class=\"event$cssClass\">")
                append("<div class=\"time\">${dateFormat.format(Date(event.timestamp))}</div>")
                append("<div class=\"type\">${escapeHtml(labelFor(event))}" +
                    (if (event.isIncognito) "<span class=\"badge\">INCOGNITO</span>" else "") +
                    (if (isEpisodeMarker) "<span class=\"badge episode\">EPISODE</span>" else "") + "</div>")
                event.detail?.let { append("<div>${escapeHtml(it)}</div>") }
                if (event.latitude != null && event.longitude != null) {
                    append("<div>Location: ${event.latitude}, ${event.longitude}" +
                        (event.locationAccuracyMeters?.let { " (±${it.toInt()}m)" } ?: "") + "</div>")
                }
                if (event.type == EventType.VOICE_MEMO) {
                    append("<div><em>Voice memo recorded -- play back in the NightGuard app.</em></div>")
                }
                event.photoPath?.let { path ->
                    val bytes = runCatching { imageStore.decryptToBytes(File(path)) }.getOrNull()
                    if (bytes != null) {
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        append("<img src=\"data:image/jpeg;base64,$b64\" alt=\"photo\">")
                    }
                }
                append("</div>")
            }
            append("</body></html>")
        }

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "nightguard_report_${System.currentTimeMillis()}.html")
        file.writeText(html)
        return file
    }

    private fun labelFor(event: TimelineEvent): String = when (event.type) {
        EventType.APP_FOREGROUND -> "App opened: ${event.appLabel ?: event.packageName ?: ""}"
        EventType.INCOGNITO_DETECTED -> "Incognito/private browsing detected"
        EventType.SETTINGS_OR_PERMISSION_ACCESS -> "Settings screen opened"
        EventType.UNLOCK_SELFIE -> "Photo captured"
        EventType.USER_SWITCH -> "User/profile switch"
        EventType.LOCATION_LOG -> "Location logged"
        EventType.DEVICE_CONNECTION -> "Device connection"
        EventType.VOICE_MEMO -> "Voice memo"
        EventType.TAMPER_ATTEMPT -> "NightGuard protection changed"
        EventType.MONITORING_STATE -> "Monitoring paused/resumed"
        EventType.BROWSING_ACTIVITY -> "Page visited"
        EventType.EPISODE_MARKER -> "Episode marker"
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
