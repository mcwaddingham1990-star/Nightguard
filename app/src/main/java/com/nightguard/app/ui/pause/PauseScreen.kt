@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nightguard.app.ui.pause

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.spacedBy
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import com.nightguard.app.util.MonitoringState
import com.nightguard.app.util.SecurePrefs
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private data class PauseOption(val label: String, val millis: Long)

private val PAUSE_OPTIONS = listOf(
    PauseOption("30 minutes", 30 * 60_000L),
    PauseOption("1 hour", 60 * 60_000L),
    PauseOption("4 hours (max)", MonitoringState.MAX_PAUSE_MS)
)

/**
 * Pausing (weakening protection) requires the recovery PIN you set while lucid, during
 * setup. Resuming early (strengthening protection) never does -- friction only applies to
 * the direction that matters. A pause always auto-expires on its own; there's no way to
 * pause "indefinitely."
 */
@Composable
fun PauseScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { SecurePrefs(context) }
    val repo = remember(context) { TimelineRepository(context) }
    var pausedUntil by remember { mutableLongStateOf(prefs.pausedUntil()) }
    var pinInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isPaused = pausedUntil > System.currentTimeMillis()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Pause monitoring") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isPaused) {
                Text("Monitoring is paused until ${timeFormat.format(pausedUntil)}.")
                Button(onClick = {
                    prefs.clearPause()
                    pausedUntil = 0L
                    scope.launch {
                        repo.log(type = EventType.MONITORING_STATE, detail = "Monitoring resumed early")
                    }
                    onDone()
                }) { Text("Resume monitoring now") }
            } else {
                Text("Monitoring is active.")
                Text("Enter your recovery PIN to pause it. This is the same PIN you set up during setup.")
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it; error = null },
                    label = { Text("Recovery PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PAUSE_OPTIONS.forEach { option ->
                        Button(onClick = {
                            if (!prefs.hasPin()) {
                                error = "No recovery PIN is set up yet -- set one on the Setup screen first."
                                return@Button
                            }
                            if (!prefs.verifyPin(pinInput)) {
                                error = "Incorrect PIN."
                                return@Button
                            }
                            val until = System.currentTimeMillis() + option.millis
                            prefs.setPausedUntil(until)
                            pausedUntil = until
                            scope.launch {
                                repo.log(
                                    type = EventType.MONITORING_STATE,
                                    detail = "Monitoring paused for ${option.label}, until ${timeFormat.format(until)}"
                                )
                            }
                            onDone()
                        }) { Text(option.label) }
                    }
                }
            }
        }
    }
}
