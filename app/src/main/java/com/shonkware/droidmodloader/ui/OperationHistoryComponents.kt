package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults

@Composable
internal fun RecentOperationsCard(history: OperationHistoryPresentation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recent operations", fontWeight = FontWeight.Bold)
            Text(history.status, style = MaterialTheme.typography.bodySmall)
            history.entries.forEach { entry ->
                key(entry.id) {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Text("${entry.title}: ${entry.outcome}", fontWeight = FontWeight.Bold)
                    Text(entry.context, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(entry.reason, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.semantics { contentDescription = "Details for attempt ${entry.id}" }
                    ) { Text(if (expanded) "Hide details" else "Details") }
                    if (expanded) Text("${entry.context}\n${entry.details}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("History is not current health or recovery authority.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
