package com.shonkware.droidmodloader.engine.model

import com.shonkware.droidmodloader.engine.index.ModContentCategory
import com.shonkware.droidmodloader.engine.index.ModContentEntry
import com.shonkware.droidmodloader.engine.index.ModFileIndexEntry

/** Typed identities prevent a library ID from impersonating a profile's Overwrite. */
internal sealed interface EffectiveSourceIdentity {
    val gameId: String
    val contentRevision: Long

    data class LibraryInstallation(
        override val gameId: String,
        val installationId: String,
        override val contentRevision: Long
    ) : EffectiveSourceIdentity

    data class ProfileOverwrite(
        override val gameId: String,
        val profileId: String,
        override val contentRevision: Long
    ) : EffectiveSourceIdentity
}

internal data class EffectiveSourceProvider(
    val identity: EffectiveSourceIdentity,
    val displayName: String,
    val libraryPriority: Int?,
    val sourceArchiveName: String?
) {
    init {
        when (identity) {
            is EffectiveSourceIdentity.LibraryInstallation -> require(libraryPriority != null && libraryPriority > 0)
            is EffectiveSourceIdentity.ProfileOverwrite -> require(libraryPriority == null && sourceArchiveName == null)
        }
    }
}

internal data class EffectiveFileProvider(
    val provider: EffectiveSourceProvider,
    val role: DeployScope,
    val indexEntry: ModFileIndexEntry,
    val contentEntry: ModContentEntry,
    val sourceFilePath: String
) {
    init {
        require(role == DeployScope.DATA || role == DeployScope.GAME_ROOT)
        require(contentEntry.originalPath == indexEntry.originalPath && contentEntry.normalizedPath == indexEntry.normalizedPath)
        require(contentEntry.deployScope == role)
    }
}

internal data class EffectiveSourcePath(val role: DeployScope, val normalizedPath: String)

internal data class EffectiveResolvedPath(
    val path: EffectiveSourcePath,
    val winner: EffectiveFileProvider,
    val allProviders: List<EffectiveFileProvider>,
    val displacedProviders: List<EffectiveFileProvider>
)

/** One complete captured projection, not deployment or plugin activation. There is no
 * enabled checkbox, normal-mod surrogate or synthetic Int.MAX_VALUE priority for Overwrite. */
internal data class EffectiveProfileContent(
    val gameId: String,
    val profileId: String,
    val libraryRevision: Long,
    val membershipRevision: Long,
    val overwriteRecordRevision: Long?,
    val overwriteContentRevision: Long,
    val paths: List<EffectiveResolvedPath>
) {
    val deployableFiles: List<EffectiveFileProvider> get() = paths.map { it.winner }.filter { it.contentEntry.isDeployable }

    /** Discovery inputs only. The existing per-game policy owns activation and order. */
    val pluginCandidates: List<EffectiveFileProvider> get() = deployableFiles.filter {
        it.role == DeployScope.DATA && it.contentEntry.category == ModContentCategory.PLUGIN
    }
}
