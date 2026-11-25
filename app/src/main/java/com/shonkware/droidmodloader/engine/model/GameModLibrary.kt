package com.shonkware.droidmodloader.engine.model

import java.io.File

/** Shared installation facts. Profile activation/priority never belongs here. */
internal data class GameLibraryEntry(
    val installationId: String,
    val payloadRevision: Long,
    val installedMetadata: InstalledModRecord
)

internal data class GameModLibrary(
    val gameId: String,
    val revision: Long,
    val entries: List<GameLibraryEntry>
)

internal data class ProfileModChoice(
    val installationId: String,
    val enabled: Boolean,
    val priority: Int
)

internal data class ProfileModMembership(
    val gameId: String,
    val profileId: String,
    val revision: Long,
    val choices: List<ProfileModChoice>
)

/** A record join, not proof of payload availability, index completeness or deployment. */
internal data class ProfileLibraryReference(
    val choice: ProfileModChoice,
    val libraryEntry: GameLibraryEntry?,
    val expectedInstallDirectory: File?
)
