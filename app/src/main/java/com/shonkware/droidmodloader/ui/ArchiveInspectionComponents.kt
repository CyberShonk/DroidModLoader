package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.ui.archive.ArchiveInspectionReport
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ArchiveInspectionSummary(report: ArchiveInspectionReport) {
    var showDetails by rememberSaveable(report.archiveName, report.observedAtMillis) { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("From the last install attempt: ${report.archiveName}", style = MaterialTheme.typography.titleSmall)
            val facts = report.inspection?.extraction
            Text(if (facts == null) "Inspection unavailable: ${report.failureCode}" else
                "${facts.format.diagnosticLabel.uppercase()} read completed • ${facts.completedFiles} files • " +
                    formatArchiveSize(facts.expandedBytes))
            Text("The archive may have changed since this attempt.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showDetails = true }) { Text("Archive Details") }
        }
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Archive details from install attempt") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    archiveInspectionDetails(report).forEach { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } }
        )
    }
}

internal fun archiveInspectionDetails(report: ArchiveInspectionReport): List<String> = buildList {
    add("Archive: ${report.archiveName}")
    add("Observed: ${DateFormat.getDateTimeInstance().format(Date(report.observedAtMillis))}")
    add("These facts describe one temporary snapshot. The source may have changed. Installing reads a fresh snapshot.")
    val inspection = report.inspection
    if (inspection == null) {
        add("Inspection unavailable: ${report.failureCode ?: "unknown"}")
        add(report.message ?: "No completed read facts are available.")
    } else {
        val facts = inspection.extraction
        add("Detected from signature: ${facts.format.diagnosticLabel}")
        add("Extension mismatch: ${if (facts.extensionMismatch) "yes" else "no"}")
        add("Reader: completed under DML extraction limits. This does not prove game compatibility.")
        add("Archive: ${facts.archiveBytes} bytes; expanded output: ${facts.expandedBytes} bytes")
        add("Accepted entries: ${facts.acceptedEntries}; completed files: ${facts.completedFiles}")
        fun observed(value: Boolean?) = when (value) { true -> "yes"; false -> "no"; null -> "not separately observed" }
        add("Encrypted: ${observed(facts.variant.encrypted)}")
        add("Multipart: ${observed(facts.variant.multipart)}")
        add("Solid: ${observed(facts.variant.solid)}")
        add("Compression method codes: ${facts.variant.compressionMethods?.sorted()?.joinToString()?.ifEmpty { "no regular files" } ?: "not separately observed"}")
        add("Installer layout: ${inspection.installerType?.name ?: "unavailable"}")
        inspection.layoutIssue?.let { add(it) }
        add("A readable archive and a recognized installer layout are different results. No installation or deployment is implied.")
    }
}
