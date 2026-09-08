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
 * incognito/private-browsing indicator in known browser apps (plus whatever
 * page title/address-bar text is visible on screen at that moment -- private
 * mode stops the browser from writing history to disk, it doesn't stop
 * anything from being rendered on screen, which is what this reads live),
 * navigation into Settings screens someone could use to hide activity or
 * weaken NightGuard itself, navigation into a browser's own in-app
 * settings (clear browsing data, sync, site settings, incognito-tab
 * locking), and a continuous log of page navigations (URL bar / page
 * title) in known browsers, tagged with whether incognito was active at
 * the time. Every Settings screen gets logged for an audit trail; the
 * sensitive subset also triggers a selfie. Password/secure-entry fields
 * are never read, in any of the above -- see collectTexts().
 */
class NightGuardAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: TimelineRepository

    private var lastIncognitoPackage: String? = null
    private var lastLoggedClassName: String? = null
    private var lastSensitiveScreenKey: String? = null
    private var lastBrowserSettingsKey: String? = null
    private var lastBrowsingKey: String? = null

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
            checkForBrowserSettingsScreen(packageName)
            checkForPageNavigation(packageName)
        }

        if (packageName == SETTINGS_PACKAGE) {
            val isScreenTransition = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            checkForSensitiveSettingsScreen(packageName, e.className?.toString(), isScreenTransition)
        }
    }

    private fun checkForIncognito(packageName: String) {
        val root = rootInActiveWindow ?: return
        val hasIncognitoIndicator = containsIncognitoText(root)
        val visibleText = if (hasIncognitoIndicator) {
            val texts = mutableListOf<String>()
            collectTexts(root, texts)
            texts.distinct().take(8).joinToString(" | ")
        } else null
        root.recycle()

        if (hasIncognitoIndicator && lastIncognitoPackage != packageName) {
            lastIncognitoPackage = packageName
            val detail = "Private/incognito browsing indicator detected" +
                (visibleText?.takeIf { it.isNotBlank() }?.let { " -- visible on screen: $it" } ?: "")
            log(EventType.INCOGNITO_DETECTED, packageName, detail, isIncognito = true)
            scope.launch { UnlockCaptureService.start(applicationContext, detail) }
        } else if (!hasIncognitoIndicator && lastIncognitoPackage == packageName) {
            lastIncognitoPackage = null
        }
    }

    private fun containsIncognitoText(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 12) return false
        val text = if (node.isPassword) null else node.text?.toString()?.lowercase()
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

    /**
     * A browser's own Settings screen (clear browsing data, sync, site settings, incognito
     * lock) lives inside the browser app, not com.android.settings, so it needs its own
     * title-text scan rather than the Settings-package className path below.
     */
    private fun checkForBrowserSettingsScreen(packageName: String) {
        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        root.recycle()

        val match = BROWSER_SETTINGS_TITLE_KEYWORDS.entries
            .firstOrNull { (key, _) -> texts.any { it.equals(key, ignoreCase = true) } }
            ?: run { lastBrowserSettingsKey = null; return }

        if (lastBrowserSettingsKey == match.value) return
        lastBrowserSettingsKey = match.value
        log(EventType.SETTINGS_OR_PERMISSION_ACCESS, packageName, "Browser settings screen opened: ${match.value}")

        if (match.value in BROWSER_SETTINGS_TRIGGERING_SELFIE) {
            scope.launch {
                UnlockCaptureService.start(applicationContext, "Browser settings screen opened: ${match.value}")
            }
        }
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
     * A continuous browsing log, not just the incognito-trigger moment: every time the
     * visible URL/page title changes in a known browser, it gets a timeline entry, tagged
     * isIncognito when it happened while the incognito indicator was showing for this
     * package. This deliberately stays scoped to browser page titles/URLs -- it is not a
     * general on-screen text logger for every app, which would start capturing messages,
     * banking info, anything else rendered on screen well beyond "which websites."
     */
    private fun checkForPageNavigation(packageName: String) {
        val root = rootInActiveWindow ?: return
        val urlBarText = findUrlBarText(root)
        val fallbackTitle = if (urlBarText == null) {
            val texts = mutableListOf<String>()
            collectTexts(root, texts, maxDepth = 6)
            texts.firstOrNull { it.length in 3..120 }
        } else null
        root.recycle()

        val pageText = urlBarText ?: fallbackTitle ?: return
        val key = "$packageName|$pageText"
        if (key == lastBrowsingKey) return
        lastBrowsingKey = key

        // Banking sites are never logged, on request -- checked against the same page
        // text (URL/title) everything else here uses. Best-effort keyword list; add your
        // own bank's domain if it slips through.
        if (EXCLUDED_SITE_KEYWORDS.any { pageText.contains(it, ignoreCase = true) }) return

        val isIncognito = lastIncognitoPackage == packageName
        scope.launch {
            repo.log(
                type = EventType.BROWSING_ACTIVITY,
                packageName = packageName,
                detail = pageText,
                isIncognito = isIncognito
            )
        }
    }

    /** Looks for a Chrome/Chromium-style address-bar node by resource id; best-effort across browsers. */
    private fun findUrlBarText(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 16) return null
        val resId = node.viewIdResourceName
        if (resId != null && URL_BAR_RESOURCE_ID_SUFFIXES.any { resId.endsWith(it) } && !node.isPassword) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findUrlBarText(child, depth + 1)
            child.recycle()
            if (found != null) return found
        }
        return null
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

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int = 0, maxDepth: Int = 14) {
        if (depth > maxDepth || out.size > 60) return
        // Never capture password/secure-entry field content, regardless of what's being
        // scanned for -- this guard applies everywhere collectTexts is used.
        if (!node.isPassword) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, depth + 1, maxDepth)
            child.recycle()
        }
    }

    private fun log(type: EventType, packageName: String, detail: String, isIncognito: Boolean = false) {
        scope.launch {
            repo.log(type = type, packageName = packageName, detail = detail, isIncognito = isIncognito)
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

        // Address-bar view-id suffixes across Chromium-based and other browsers.
        // Best-effort, same caveat as everywhere else in this file: browsers rename these
        // between versions, so a browser that never logs page navigations needs its actual
        // resource id added here.
        private val URL_BAR_RESOURCE_ID_SUFFIXES = listOf(
            "url_bar", // Chrome, Brave, Edge, other Chromium-based browsers
            "toolbar_edit_url_text", // Firefox
            "browser_toolbar_url" // Samsung Internet
        )

        // Sites never logged by the continuous browsing log, on request -- matched against
        // the same URL/title text everything else here uses. Best-effort by nature (a
        // keyword/domain list, not a real site classifier): add your own bank/credit union
        // if one gets through, or remove entries you'd rather keep visible.
        private val EXCLUDED_SITE_KEYWORDS = listOf(
            "chase.com", "bankofamerica.com", "wellsfargo.com", "citibank.com", "citi.com",
            "usbank.com", "capitalone.com", "pnc.com", "truist.com", "ally.com",
            "schwab.com", "fidelity.com", "vanguard.com", "discover.com", "amex.com",
            "americanexpress.com", "navyfederal.org", "usaa.com", "creditkarma.com",
            "credit union", "banking online", " bank "
        )

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

        // In-app browser settings screens (Chrome-style wording; other Chromium-based
        // browsers use near-identical labels). Best-effort, same caveat as above: tell
        // me the exact screen title if one doesn't fire on your browser.
        private val BROWSER_SETTINGS_TITLE_KEYWORDS = linkedMapOf(
            "Clear browsing data" to "Clear browsing data",
            "Privacy and security" to "Privacy and security settings",
            "Sync and Google services" to "Sync settings",
            "Site settings" to "Site settings",
            "Lock incognito tabs when you close Chrome" to "Incognito-tab lock setting",
            "Passwords" to "Saved passwords",
            "Password Manager" to "Saved passwords",
            "Payment methods" to "Saved payment methods",
            "Addresses and more" to "Saved addresses",
            "History" to "Browsing history screen"
        )

        // Subset of the above that's worth a selfie on top of the audit log entry --
        // the ones that specifically erase or hide evidence of browsing activity.
        private val BROWSER_SETTINGS_TRIGGERING_SELFIE = setOf(
            "Clear browsing data",
            "Incognito-tab lock setting"
        )
    }
}
