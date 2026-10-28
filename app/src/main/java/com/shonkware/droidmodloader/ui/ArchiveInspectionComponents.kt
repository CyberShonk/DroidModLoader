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
import com.shonkware.droidmodloader.engine.install.InstallerType
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ArchiveInspectionSummary(report: ArchiveInspectionReport) {
    var showDetails by rememberSaveable(report.archiveName, report.observedAtMillis) { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("From the last install attempt: ${report.archiveName}", style = MaterialTheme.typography.titleSmall)
            val facts = report.inspection?.extraction
            Text(if (facts == null) "Archive details unavailable: ${report.failureCode}" else
                "${facts.format.diagnosticLabel.uppercase()} • ${facts.completedFiles} files • " +
                    "${formatArchiveSize(facts.expandedBytes)} unpacked")
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

internal fun installerTypeLabel(type: InstallerType?): String = when (type) {
    InstallerType.SIMPLE -> "Simple"
    InstallerType.BAIN -> "BAIN"
    InstallerType.FOMOD -> "FOMOD"
    InstallerType.MANUAL_DATA_FOLDER -> "Choose a Data folder"
    null -> "Not recognized"
}

internal fun archiveInspectionDetails(report: ArchiveInspectionReport): List<String> = buildList {
    add("Archive: ${report.archiveName}")
    add("Read at: ${DateFormat.getDateTimeInstance().format(Date(report.observedAtMillis))}")
    add("These details were gathered during an install attempt. The source archive may have changed. A new install reads the archive again.")
    val inspection = report.inspection
    if (inspection == null) {
        add("Archive details unavailable: ${report.failureCode ?: "unknown"}")
        add(report.message ?: "The archive was not fully read.")
    } else {
        val facts = inspection.extraction
        add("Archive type: ${facts.format.diagnosticLabel}")
        add("Filename extension matches archive type: ${if (facts.extensionMismatch) "no" else "yes"}")
        add("Archive was unpacked within DML's safety limits. This does not prove the mod will work in the game.")
        add("Archive size: ${facts.archiveBytes} bytes; unpacked size: ${facts.expandedBytes} bytes")
        add("Archive entries: ${facts.acceptedEntries}; files unpacked: ${facts.completedFiles}")
        fun observed(value: Boolean?) = when (value) { true -> "yes"; false -> "no"; null -> "unknown" }
        add("Encrypted: ${observed(facts.variant.encrypted)}")
        add("Split across volumes: ${observed(facts.variant.multipart)}")
        add("Solid: ${observed(facts.variant.solid)}")
        add("Compression method codes: ${facts.variant.compressionMethods?.sorted()?.joinToString()?.ifEmpty { "no regular files" } ?: "unknown"}")
        add("Installer: ${installerTypeLabel(inspection.installerType)}")
        inspection.layoutIssue?.let { add(it) }
        add("Unpacking an archive does not mean installation or deployment finished.")
    }
}
