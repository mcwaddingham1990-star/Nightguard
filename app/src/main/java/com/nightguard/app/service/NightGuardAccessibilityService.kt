package com.nightguard.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nightguard.app.capture.UnlockCaptureService
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watches window content for signals other apps can't expose directly: an
 * incognito/private-browsing indicator in known browser apps, and navigation
 * into Settings screens someone could use to hide activity or weaken
 * NightGuard itself (accessibility, app permissions, usage access,
 * notification access, developer options, factory reset, lock screen,
 * date/time, app info for uninstall/force-stop). Every Settings screen gets
 * logged for an audit trail; the sensitive subset also triggers a selfie.
 */
class NightGuardAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: TimelineRepository

    private var lastIncognitoPackage: String? = null
    private var lastLoggedClassName: String? = null
    private var lastSensitiveScreenKey: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        repo = TimelineRepository(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val packageName = e.packageName?.toString() ?: return

        if (packageName in KNOWN_BROWSER_PACKAGES) {
            checkForIncognito(packageName)
        }

        if (packageName == SETTINGS_PACKAGE) {
            val isScreenTransition = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            checkForSensitiveSettingsScreen(packageName, e.className?.toString(), isScreenTransition)
        }
    }

    private fun checkForIncognito(packageName: String) {
        val root = rootInActiveWindow ?: return
        val hasIncognitoIndicator = containsIncognitoText(root)
        root.recycle()
        if (hasIncognitoIndicator && lastIncognitoPackage != packageName) {
            lastIncognitoPackage = packageName
            log(EventType.INCOGNITO_DETECTED, packageName, "Private/incognito browsing indicator detected")
        } else if (!hasIncognitoIndicator && lastIncognitoPackage == packageName) {
            lastIncognitoPackage = null
        }
    }

    private fun containsIncognitoText(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 12) return false
        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        if (INCOGNITO_KEYWORDS.any { text?.contains(it) == true || desc?.contains(it) == true }) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsIncognitoText(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun checkForSensitiveSettingsScreen(packageName: String, className: String?, isScreenTransition: Boolean) {
        // Only a real window-state transition reliably reports the screen's own class.
        // Content-changed events report whatever inner view redrew (ScrollView,
        // RecyclerView, FrameLayout, ...), which is meaningless as a "screen" and was
        // flooding the timeline with noise -- so those don't get an audit log entry,
        // but still feed the title-text scan below in case a screen's content loads in late.
        if (isScreenTransition && className != null && lastLoggedClassName != className) {
            lastLoggedClassName = className
            log(EventType.SETTINGS_OR_PERMISSION_ACCESS, packageName, "Settings screen opened: $className")
        }

        val sensitiveLabel = detectSensitiveLabel(className)
        if (sensitiveLabel == null) {
            lastSensitiveScreenKey = null
            return
        }
        if (lastSensitiveScreenKey == sensitiveLabel) return
        lastSensitiveScreenKey = sensitiveLabel
        scope.launch {
            UnlockCaptureService.start(applicationContext, "Settings screen opened: $sensitiveLabel")
        }
    }

    /**
     * Two-layer detection: try the (AOSP-derived) fragment class name first, since it's
     * cheap and still works on many devices/versions. Fall back to reading the on-screen
     * title/heading text, which is what actually survives OEM skinning (Samsung One UI and
     * modern stock Android both route most sub-screens through a single generic host
     * Activity, so the class name alone often can't tell screens apart).
     */
    private fun detectSensitiveLabel(className: String?): String? {
        if (className != null) {
            SENSITIVE_SETTINGS_CLASSNAMES.entries
                .firstOrNull { (key, _) -> className.contains(key, ignoreCase = true) }
                ?.let { return it.value }
        }

        val root = rootInActiveWindow ?: return null
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        root.recycle()

        SENSITIVE_TITLE_KEYWORDS.entries
            .firstOrNull { (key, _) -> texts.any { it.equals(key, ignoreCase = true) } }
            ?.let { return it.value }

        // A per-app "App info" page is titled with the app's own name, so it can't be
        // matched by a fixed heading string; its "Force stop" button is a reliable proxy.
        if (texts.any { it.equals("Force stop", ignoreCase = true) }) {
            return "App info screen (uninstall / force stop / clear data)"
        }
        return null
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int = 0) {
        if (depth > 14 || out.size > 60) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, depth + 1)
            child.recycle()
        }
    }

    private fun log(type: EventType, packageName: String, detail: String) {
        scope.launch {
            repo.log(type = type, packageName = packageName, detail = detail)
        }
    }

    override fun onInterrupt() {}

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"

        private val KNOWN_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android"
        )

        private val INCOGNITO_KEYWORDS = listOf("incognito", "private tab", "private browsing")

        // Settings screens that could be used to hide activity or weaken NightGuard's
        // own ability to watch the device, mapped className-fragment -> human label.
        // Matched against AOSP fragment class names; OEM skins (Samsung/OnePlus/etc.)
        // may rename these and will need entries added here.
        private val SENSITIVE_SETTINGS_CLASSNAMES = linkedMapOf(
            "AccessibilitySettings" to "Accessibility settings",
            "AccessibilityDetailsSetting" to "Accessibility service details",
            "ManageApplications" to "App list / manage apps",
            "InstalledAppDetails" to "App info (uninstall / force stop / clear data)",
            "AppPermissionsFragment" to "App permissions",
            "PermissionAppsFragment" to "App permissions",
            "AppOpsSummary" to "Special app access",
            "SpecialAccessSettings" to "Special app access",
            "UsageAccessSettings" to "Usage access permission",
            "HighPowerApplicationsFragment" to "Battery optimization exemptions",
            "NotificationAccessSettings" to "Notification access",
            "NotificationStation" to "Notification access",
            "ZenModeSettings" to "Do Not Disturb settings",
            "DeviceAdminSettings" to "Device admin apps",
            "DeviceAdminAdd" to "Device admin apps",
            "ManageAppExternalSourcesActivity" to "Install unknown apps permission",
            "DevelopmentSettings" to "Developer options",
            "MasterClear" to "Factory reset",
            "ChooseLockGeneric" to "Screen lock settings",
            "ChooseLockPassword" to "Screen lock settings",
            "ChooseLockPattern" to "Screen lock settings",
            "DateTimeSettings" to "Date & time settings"
        )

        // On-screen heading text for the same sensitive screens, matched exactly
        // (case-insensitive) against the current window's title/heading. This is what
        // actually catches Samsung One UI and other OEM-skinned Settings apps, where
        // internal class names no longer map cleanly to visible sub-screens.
        // Best-effort: exact wording varies by One UI/Android version, so anything that
        // doesn't fire on your phone is a wording mismatch, not a missing feature.
        private val SENSITIVE_TITLE_KEYWORDS = linkedMapOf(
            "Accessibility" to "Accessibility settings",
            "Installed services" to "Accessibility settings",
            "Permission manager" to "App permissions",
            "App permissions" to "App permissions",
            "Special access" to "Special app access",
            "Usage access" to "Usage access permission",
            "Usage data access" to "Usage access permission",
            "Battery optimization" to "Battery optimization exemptions",
            "Notification access" to "Notification access",
            "Do not disturb" to "Do Not Disturb settings",
            "Device admin apps" to "Device admin apps",
            "Install unknown apps" to "Install unknown apps permission",
            "Developer options" to "Developer options",
            "Reset options" to "Factory reset",
            "Factory data reset" to "Factory reset",
            "Screen lock type" to "Screen lock settings",
            "Screen lock" to "Screen lock settings",
            "Lock screen" to "Screen lock settings",
            "Date and time" to "Date & time settings",
            // Stock-Android hiding mechanisms: Private Space is Google's own hidden,
            // separately-locked space for apps (Android 15+); "Hide apps" hides icons
            // from the launcher on both stock Android and most OEM skins.
            "Private space" to "Private Space settings",
            "Set up private space" to "Private Space settings",
            "Hide apps" to "Hide-apps setting opened",
            // Samsung-specific, harmless no-op on non-Samsung phones: Secure Folder is
            // the most common way to hide apps/photos on a Galaxy device.
            "Secure Folder" to "Secure Folder settings"
        )
    }
}
