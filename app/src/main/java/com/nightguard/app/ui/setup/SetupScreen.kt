@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nightguard.app.ui.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nightguard.app.util.PermissionUtils

private data class SetupStep(
    val title: String,
    val description: String,
    val isGranted: (Context) -> Boolean,
    val action: (Context) -> Unit
)

private val STEPS = listOf(
    SetupStep(
        title = "Usage access",
        description = "Lets NightGuard see which app is in the foreground and when.",
        isGranted = { PermissionUtils.hasUsageAccess(it) },
        action = { it.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    ),
    SetupStep(
        title = "Accessibility service",
        description = "Lets NightGuard notice incognito/private browsing and sensitive settings screens.",
        isGranted = { PermissionUtils.isAccessibilityServiceEnabled(it) },
        action = { it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    ),
    SetupStep(
        title = "Camera",
        description = "Lets NightGuard take a photo when the device is unlocked.",
        isGranted = {
            ContextCompat.checkSelfPermission(it, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        },
        action = { ctx ->
            (ctx as? Activity)?.let {
                ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.CAMERA), 100)
            }
        }
    ),
    SetupStep(
        title = "Notifications",
        description = "Required so the always-on monitoring service can run.",
        isGranted = {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(it, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        },
        action = { ctx ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                (ctx as? Activity)?.let {
                    ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                }
            }
        }
    ),
    SetupStep(
        title = "Ignore battery optimization",
        description = "Stops Android from killing NightGuard's background monitoring.",
        isGranted = {
            val pm = it.getSystemService(PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(it.packageName) == true
        },
        action = {
            it.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}"))
            )
        }
    )
)

@Composable
fun SetupScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val allGranted = STEPS.all { it.isGranted(context) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("NightGuard setup") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                items(STEPS) { step ->
                    val granted = step.isGranted(context)
                    ListItem(
                        headlineContent = { Text(step.title) },
                        supportingContent = { Text(step.description) },
                        leadingContent = {
                            Icon(
                                if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null
                            )
                        },
                        trailingContent = {
                            if (!granted) {
                                Button(onClick = {
                                    step.action(context)
                                    refreshKey++
                                }) { Text("Grant") }
                            }
                        }
                    )
                }
            }
            Button(
                onClick = onContinue,
                enabled = allGranted,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(if (allGranted) "Start monitoring" else "Grant all permissions to continue")
            }
        }
    }
}
