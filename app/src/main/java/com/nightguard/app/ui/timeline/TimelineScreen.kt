@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nightguard.app.ui.timeline

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nightguard.app.capture.VoiceMemoRecorder
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import com.nightguard.app.data.db.TimelineEvent
import com.nightguard.app.util.SecureAudioStore
import com.nightguard.app.util.SecureImageStore
import com.nightguard.app.util.TimeFormat
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun TimelineScreen(
    onOpenSetup: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenPause: () -> Unit,
    onOpenBrowsing: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { TimelineRepository(context) }
    val imageStore = remember(context) { SecureImageStore(context) }
    val audioStore = remember(context) { SecureAudioStore(context) }
    val recorder = remember(context) { VoiceMemoRecorder(context) }
    val events by repo.observeEvents().collectAsState(initial = emptyList())
    var photoToShow by remember { mutableStateOf<File?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var playingPath by remember { mutableStateOf<String?>(null) }

    // events is sorted newest-first, so the first EPISODE_MARKER entry is the current state.
    val lastEpisodeMarker = remember(events) { events.firstOrNull { it.type == EventType.EPISODE_MARKER } }
    val episodeActive = lastEpisodeMarker?.detail?.startsWith("Episode started") == true

    fun toggleEpisode() {
        scope.launch {
            if (episodeActive) {
                val duration = lastEpisodeMarker?.timestamp?.let { formatDuration(System.currentTimeMillis() - it) }
                repo.log(
                    type = EventType.EPISODE_MARKER,
                    detail = "Episode ended" + (duration?.let { " (lasted $it)" } ?: "")
                )
            } else {
                repo.log(type = EventType.EPISODE_MARKER, detail = "Episode started")
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && recorder.start()) isRecording = true
    }

    fun toggleRecording() {
        if (isRecording) {
            scope.launch {
                recorder.stopAndSave()
                isRecording = false
            }
            return
        }
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            isRecording = recorder.start()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NightGuard timeline") },
                actions = {
                    IconButton(onClick = onOpenBrowsing) {
                        Icon(Icons.Filled.Language, contentDescription = "Browsing log")
                    }
                    IconButton(onClick = onOpenPause) {
                        Icon(Icons.Filled.PauseCircle, contentDescription = "Pause monitoring")
                    }
                    IconButton(onClick = onOpenReport) {
                        Icon(Icons.Filled.Assessment, contentDescription = "Doctor report")
                    }
                    IconButton(onClick = onOpenSetup) {
                        Icon(Icons.Filled.Settings, contentDescription = "Setup")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { toggleRecording() }) {
                Icon(
                    if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (isRecording) "Stop voice memo" else "Record voice memo"
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            EpisodeMarkerBar(
                episodeActive = episodeActive,
                startedAt = lastEpisodeMarker?.timestamp?.takeIf { episodeActive },
                onToggle = { toggleEpisode() }
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { event ->
                    TimelineRow(
                        event = event,
                        onViewPhoto = { path -> photoToShow = File(path) },
                        isPlaying = playingPath == event.audioPath,
                        onTogglePlay = { path ->
                            playingPath = if (playingPath == path) null else path
                        }
                    )
                }
            }
        }
    }

    photoToShow?.let { file ->
        val bytes = remember(file) { runCatching { imageStore.decryptToBytes(file) }.getOrNull() }
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

    playingPath?.let { path ->
        AudioPlayerEffect(audioStore, File(path)) { playingPath = null }
    }
}

/** Decrypts the memo to a cache temp file, plays it, and cleans up when playback ends or this leaves composition. */
@Composable
private fun AudioPlayerEffect(store: SecureAudioStore, file: File, onFinished: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(file) {
        val tempFile = File.createTempFile("play_", ".m4a", context.cacheDir)
        val player = MediaPlayer()
        val bytes = runCatching { store.decryptToBytes(file) }.getOrNull()
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

/**
 * Bounds an episode explicitly, on request (start now / end now), rather than leaving
 * everything as one continuous log -- lets a doctor look at exactly the window that
 * matters instead of reconstructing it from raw activity.
 */
@Composable
private fun EpisodeMarkerBar(episodeActive: Boolean, startedAt: Long?, onToggle: () -> Unit) {
    val timeFormat = remember { TimeFormat.timeOnly() }
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Button(
            onClick = onToggle,
            colors = if (episodeActive) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (episodeActive) {
                    "Episode in progress (started ${startedAt?.let { TimeFormat.format(it, timeFormat) } ?: "?"}) — tap to mark end"
                } else {
                    "Mark episode start"
                }
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
private fun TimelineRow(
    event: TimelineEvent,
    onViewPhoto: (String) -> Unit,
    isPlaying: Boolean,
    onTogglePlay: (String) -> Unit
) {
    val timeFormat = remember { TimeFormat.shortDateTime() }
    ListItem(
        headlineContent = { Text(titleFor(event)) },
        supportingContent = {
            Text(TimeFormat.format(event.timestamp, timeFormat) + (event.detail?.let { " • $it" } ?: ""))
        },
        trailingContent = {
            Row {
                event.photoPath?.let { path ->
                    TextButton(onClick = { onViewPhoto(path) }) { Text("View") }
                }
                event.audioPath?.let { path ->
                    TextButton(onClick = { onTogglePlay(path) }) { Text(if (isPlaying) "Stop" else "Play") }
                }
            }
        }
    )
}

private fun titleFor(event: TimelineEvent): String = when (event.type) {
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
