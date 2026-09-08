@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nightguard.app.ui.report

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.spacedBy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RangeOption(val label: String, val millis: Long)

private val RANGE_OPTIONS = listOf(
    RangeOption("Last 24 hours", 24 * 60 * 60_000L),
    RangeOption("Last 7 days", 7 * 24 * 60 * 60_000L),
    RangeOption("Last 30 days", 30L * 24 * 60 * 60_000L),
    RangeOption("All time", Long.MAX_VALUE)
)

@Composable
fun ReportScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Doctor report & backup") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Generates a single shareable file summarizing the timeline (with photos) " +
                    "over the range you pick -- meant to hand to a doctor, or print."
            )
            RANGE_OPTIONS.forEach { option ->
                Button(
                    enabled = !isWorking,
                    onClick = {
                        isWorking = true
                        status = null
                        scope.launch {
                            val end = System.currentTimeMillis()
                            val start = if (option.millis == Long.MAX_VALUE) 0L else end - option.millis
                            val file = withContext(Dispatchers.IO) {
                                ReportExporter.generateReport(context, start, end)
                            }
                            isWorking = false
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/html"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share NightGuard report"))
                        }
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
