@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nightguard.app.ui.report

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.spacedBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nightguard.app.util.BackupExporter
import com.nightguard.app.util.ReportExporter
import com.nightguard.app.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

private data class RangeOption(val label: String, val millis: Long)

private val RANGE_OPTIONS = listOf(
    RangeOption("Last 24 hours", 24 * 60 * 60_000L),
    RangeOption("Last 7 days", 7 * 24 * 60 * 60_000L),
    RangeOption("Last 30 days", 30L * 24 * 60 * 60_000L),
    RangeOption("All time", Long.MAX_VALUE)
)

private val CENTRAL = TimeZone.getTimeZone("America/Chicago")

/** Opens a date picker, then a time picker, both interpreted in Central Time to match how every timestamp in the app displays. */
private fun pickDateTime(context: android.content.Context, initialMillis: Long, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance(CENTRAL).apply { timeInMillis = initialMillis }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    cal.set(Calendar.SECOND, 0)
                    onPicked(cal.timeInMillis)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                false
            ).show()
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
fun ReportScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    var customStart by remember { mutableLongStateOf(System.currentTimeMillis() - 24 * 60 * 60_000L) }
    var customEnd by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val dateTimeFormat = remember { TimeFormat.dateTime() }

    fun generateAndShare(start: Long, end: Long) {
        isWorking = true
        status = null
        scope.launch {
            val file = withContext(Dispatchers.IO) { ReportExporter.generateReport(context, start, end) }
            isWorking = false
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share NightGuard report"))
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Doctor report & backup") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Generates a single shareable file summarizing the timeline (with photos) " +
                    "over the range you pick -- meant to hand to a doctor, or print."
            )

            Text("Pick an exact window (e.g. the hours you were missing), all times Central:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = !isWorking,
                    onClick = { pickDateTime(context, customStart) { customStart = it } },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) { Text("From: " + TimeFormat.format(customStart, dateTimeFormat)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = !isWorking,
                    onClick = { pickDateTime(context, customEnd) { customEnd = it } },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) { Text("To: " + TimeFormat.format(customEnd, dateTimeFormat)) }
            }
            Button(
                enabled = !isWorking && customEnd > customStart,
                onClick = { generateAndShare(customStart, customEnd) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Generate & share this window") }
            if (customEnd <= customStart) {
                Text("\"To\" needs to be after \"From\".")
            }

            Text("Or a quick preset:")
            RANGE_OPTIONS.forEach { option ->
                Button(
                    enabled = !isWorking,
                    onClick = {
                        val end = System.currentTimeMillis()
                        val start = if (option.millis == Long.MAX_VALUE) 0L else end - option.millis
                        generateAndShare(start, end)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(option.label) }
            }

            Text("Back up the full timeline (metadata + photos) outside the app's own storage now, " +
                "in addition to the automatic backup NightGuard runs every few hours.")
            Button(
                enabled = !isWorking,
                onClick = {
                    isWorking = true
                    status = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { BackupExporter.exportBackup(context) }
                        isWorking = false
                        status = if (ok) "Backup saved to Downloads/NightGuard_Backups." else "Backup failed."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back up now") }

            if (isWorking) CircularProgressIndicator()
            status?.let { Text(it) }
        }
    }
}
