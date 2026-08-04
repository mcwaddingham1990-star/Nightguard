package com.nightguard.app.ui.timeline

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import com.nightguard.app.data.db.TimelineEvent
import com.nightguard.app.util.SecureImageStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun TimelineScreen(onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { TimelineRepository(context) }
    val store = remember(context) { SecureImageStore(context) }
    val events by repo.observeEvents().collectAsState(initial = emptyList())
    var photoToShow by remember { mutableStateOf<File?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NightGuard timeline") },
                actions = {
                    IconButton(onClick = onOpenSetup) {
                        Icon(Icons.Filled.Settings, contentDescription = "Setup")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(events, key = { it.id }) { event ->
                TimelineRow(event, onViewPhoto = { path -> photoToShow = File(path) })
            }
        }
    }

    photoToShow?.let { file ->
        val bytes = remember(file) { runCatching { store.decryptToBytes(file) }.getOrNull() }
        AlertDialog(
            onDismissRequest = { photoToShow = null },
            confirmButton = { TextButton(onClick = { photoToShow = null }) { Text("Close") } },
            text = {
                if (bytes != null) {
                    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Unlock photo",
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Could not load photo")
                }
            }
        )
    }
}

@Composable
private fun TimelineRow(event: TimelineEvent, onViewPhoto: (String) -> Unit) {
    val timeFormat = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }
    ListItem(
        headlineContent = { Text(titleFor(event)) },
        supportingContent = {
            Text(timeFormat.format(event.timestamp) + (event.detail?.let { " • $it" } ?: ""))
        },
        trailingContent = {
            event.photoPath?.let { path ->
                TextButton(onClick = { onViewPhoto(path) }) { Text("View") }
            }
        }
    )
}

private fun titleFor(event: TimelineEvent): String = when (event.type) {
    EventType.APP_FOREGROUND -> "Opened ${event.appLabel ?: event.packageName}"
    EventType.INCOGNITO_DETECTED -> "Incognito/private browsing detected in ${event.appLabel ?: event.packageName}"
    EventType.SETTINGS_OR_PERMISSION_ACCESS -> "Sensitive settings screen opened"
    EventType.UNLOCK_SELFIE -> "Device unlocked"
    EventType.USER_SWITCH -> "User/profile switch"
}
