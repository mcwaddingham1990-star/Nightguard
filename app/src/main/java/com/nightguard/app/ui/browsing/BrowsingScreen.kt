@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nightguard.app.ui.browsing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import com.nightguard.app.data.db.TimelineEvent
import com.nightguard.app.util.TimeFormat

private val INCOGNITO_HIGHLIGHT = Color(0xFFFFE0B2)

/**
 * Every browser page visited, all the time, not just during incognito -- this is the
 * "always-on text logger" tab. Incognito entries are visually highlighted rather than
 * shown separately, so nothing about this moment gets easier to skip past.
 */
@Composable
fun BrowsingScreen() {
    val context = LocalContext.current
    val repo = remember(context) { TimelineRepository(context) }
    val events by repo.observeEvents().collectAsState(initial = emptyList())
    val browsingEvents = remember(events) {
        events.filter { it.type == EventType.BROWSING_ACTIVITY || it.type == EventType.INCOGNITO_DETECTED }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Browsing") }) }
    ) { padding ->
        if (browsingEvents.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("Nothing logged yet. This fills in as browsers are used on this device.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp)
            ) {
                items(browsingEvents, key = { it.id }) { event -> BrowsingRow(event) }
            }
        }
    }
}

@Composable
private fun BrowsingRow(event: TimelineEvent) {
    val timeFormat = remember { TimeFormat.shortDateTime() }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (event.isIncognito) INCOGNITO_HIGHLIGHT else Color.Transparent
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                (if (event.isIncognito) "🕵 INCOGNITO — " else "") +
                    (event.detail ?: event.appLabel ?: event.packageName ?: "")
            )
            Text(TimeFormat.format(event.timestamp, timeFormat) + (event.appLabel?.let { " • $it" } ?: ""))
        }
    }
}
