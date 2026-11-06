package com.shonkware.droidmodloader.engine.plugins

import com.shonkware.droidmodloader.engine.model.FileRecord

/** Supplied by the existing resolver owner; this helper never rebuilds indexes or chooses winners. */
class CapturedPluginWinners(
    val profileId: String,
    val snapshotId: String,
    winningRecords: List<FileRecord>,
    ownedProviderRoots: Map<String, String>,
    /** Includes current index/winner AND alternative-provider enumeration, not just winner presence. */
    val complete: Boolean
) {
    val winningRecords: List<FileRecord> = immutablePluginFacts(winningRecords)
    val ownedProviderRoots: Map<String, String> = java.util.Collections.unmodifiableMap(ownedProviderRoots.toMap())
}

data class PluginSourceObservations(
    val observations: List<PluginHeaderObservation>,
    /** Enumeration completeness is independent of each file's header completeness. */
    val enumerationFailure: PluginFactFailure? = null
) {
    val enumerationComplete: Boolean get() = enumerationFailure == null
}
