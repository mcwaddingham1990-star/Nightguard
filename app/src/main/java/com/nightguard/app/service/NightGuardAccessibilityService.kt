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
    private var lastSettingsClass: String? = null

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
        val className = e.className?.toString()

        if (packageName in KNOWN_BROWSER_PACKAGES) {
            checkForIncognito(packageName)
        }

        if (packageName == SETTINGS_PACKAGE && className != null) {
            checkForSensitiveSettingsScreen(packageName, className)
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

    private fun checkForSensitiveSettingsScreen(packageName: String, className: String) {
        if (lastSettingsClass == className) return
        lastSettingsClass = className

        val sensitiveLabel = SENSITIVE_SETTINGS_CLASSNAMES.entries
            .firstOrNull { (key, _) -> className.contains(key, ignoreCase = true) }
            ?.value

        log(EventType.SETTINGS_OR_PERMISSION_ACCESS, packageName, "Settings screen opened: $className")

        if (sensitiveLabel != null) {
            scope.launch {
                UnlockCaptureService.start(applicationContext, "Settings screen opened: $sensitiveLabel")
            }
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
    }
}
