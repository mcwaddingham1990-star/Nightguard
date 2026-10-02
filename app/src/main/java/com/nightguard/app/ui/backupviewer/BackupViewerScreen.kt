@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nightguard.app.ui.backupviewer

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nightguard.app.data.db.EventType
import com.nightguard.app.util.BackupEvent
import com.nightguard.app.util.BackupReader
import com.nightguard.app.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Lists past backups and lets you drill into one -- decrypting its manifest and media on
 * demand, read-only. Only works on the device that made the backup (see BackupReader).
 */
@Composable
fun BackupViewerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf<List<BackupEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var playingAudioFile by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        folders = withContext(Dispatchers.IO) { BackupReader.listBackupFolders(context) }
    }

    fun openFolder(folder: String) {
        selectedFolder = folder
        isLoading = true
        scope.launch {
            events = withContext(Dispatchers.IO) { BackupReader.readManifest(context, folder) }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedFolder?.let { "Backup: ${formatFolderName(it)}" } ?: "View Backups") },
                navigationIcon = {
                    if (selectedFolder != null) {
                        IconButton(onClick = { selectedFolder = null; events = emptyList() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back to backup list")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            selectedFolder == null -> {
                if (folders.isEmpty()) {
                    Text(
                        "No backups found yet. One is made automatically every ~6 hours, or tap " +
                            "\"Back up now\" on the report screen.",
                        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                        items(folders) { folder ->
                            ListItem(
                                headlineContent = { Text(formatFolderName(folder)) },
                                modifier = Modifier.clickable { openFolder(folder) }
                            )
                        }
                    }
                }
            }
            isLoading -> {
                CircularProgressIndicator(modifier = Modifier.fillMaxSize().padding(padding))
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    items(events, key = { it.id }) { event ->
                        BackupRow(
                            event = event,
                            onViewPhoto = { fileName ->
                                scope.launch {
                                    photoBytes = withContext(Dispatchers.IO) {
                                        BackupReader.readMediaBytes(context, selectedFolder!!, fileName)
                                    }
                                }
                            },
                            isPlaying = playingAudioFile != null,
                            onTogglePlay = { fileName ->
                                playingAudioFile = if (playingAudioFile == fileName) null else fileName
                            }
                        )
                    }
                }
            }
        }
    }

    photoBytes?.let { bytes ->
        AlertDialog(
            onDismissRequest = { photoBytes = null },
            confirmButton = { TextButton(onClick = { photoBytes = null }) { Text("Close") } },
            text = {
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bitmap != null) {
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Backup photo", modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Could not load photo")
                }
            }
        )
    }

    val audioFileName = playingAudioFile
    val folder = selectedFolder
    if (audioFileName != null && folder != null) {
        BackupAudioPlayerEffect(folder, audioFileName) { playingAudioFile = null }
    }
}

@Composable
private fun BackupAudioPlayerEffect(folder: String, fileName: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(folder, fileName) {
        val tempFile = File.createTempFile("backup_play_", ".m4a", context.cacheDir)
        val player = MediaPlayer()
        val bytes = BackupReader.readMediaBytes(context, folder, fileName)
        if (bytes == null) {
            onFinished()
        } else {
            tempFile.writeBytes(bytes)
            runCatching {
                player.setDataSource(tempFile.absolutePath)
                player.setOnCompletionListener { onFinished() }
                player.prepare()
                player.start()
            }.onFailure { onFinished() }
        }
        onDispose {
            runCatching { player.stop() }
            player.release()
            tempFile.delete()
        }
    }
}

@Composable
private fun BackupRow(
    event: BackupEvent,
    onViewPhoto: (String) -> Unit,
    isPlaying: Boolean,
    onTogglePlay: (String) -> Unit
) {
    val timeFormat = remember { TimeFormat.shortDateTime() }
    ListItem(
        headlineContent = { Text(labelFor(event)) },
        supportingContent = {
            Text(TimeFormat.format(event.timestamp, timeFormat) + (event.detail?.let { " • $it" } ?: ""))
        },
        trailingContent = {
            Row {
                event.photoFile?.let { file ->
                    TextButton(onClick = { onViewPhoto(file) }) { Text("View") }
                }
                event.audioFile?.let { file ->
                    TextButton(onClick = { onTogglePlay(file) }) { Text(if (isPlaying) "Stop" else "Play") }
                }
            }
        }
    )
}

private fun labelFor(event: BackupEvent): String = when (event.type) {
    EventType.APP_FOREGROUND -> "Opened ${event.appLabel ?: event.packageName}"
    EventType.INCOGNITO_DETECTED -> "Incognito/private browsing detected in ${event.appLabel ?: event.packageName}"
    EventType.SETTINGS_OR_PERMISSION_ACCESS -> "Sensitive settings screen opened"
    EventType.UNLOCK_SELFIE -> "Photo captured"
    EventType.USER_SWITCH -> "User/profile switch"
    EventType.LOCATION_LOG -> "Location logged" +
        (event.latitude?.let { lat -> event.longitude?.let { lon -> ": %.5f, %.5f".format(lat, lon) } } ?: "")
    EventType.DEVICE_CONNECTION -> "Device connection"
    EventType.VOICE_MEMO -> "Voice memo recorded"
    EventType.TAMPER_ATTEMPT -> "NightGuard protection changed"
    EventType.MONITORING_STATE -> "Monitoring paused/resumed"
    EventType.BROWSING_ACTIVITY -> (if (event.isIncognito) "[Incognito] " else "") + "Page visited"
    EventType.EPISODE_MARKER -> "Episode marker"
}

private fun formatFolderName(folder: String): String =
    runCatching {
        val parsed = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(folder)
        parsed?.let { TimeFormat.format(it.time, TimeFormat.dateTime()) }
    }.getOrNull() ?: folder
