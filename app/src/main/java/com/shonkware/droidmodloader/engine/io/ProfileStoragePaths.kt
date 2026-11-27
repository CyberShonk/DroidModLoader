package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.model.DeployScope
import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

internal class ProfileStoragePaths(
    private val filesDir: File,
    private val activeProfileIdProvider: () -> String?,
    private val selectedGameIdProvider: () -> String
) {

    fun getActiveProfileStorageKey(): String {
        val profileId = activeProfileIdProvider()

        return if (!profileId.isNullOrBlank()) {
            sanitizeStorageName(profileId)
        } else {
            sanitizeStorageName("unassigned_${selectedGameIdProvider()}")
        }
    }

    fun getProfileInternalDir(): File {
        return File(filesDir, "profiles/${getActiveProfileStorageKey()}")
    }

    fun getProfileStateDir(externalBaseDir: File): File {
        return File(externalBaseDir, "state/profiles/${getActiveProfileStorageKey()}")
    }

    fun getManagedInternalDirectory(): File = checkedLibraryPath("")

    // Migration inventory only; callers must reject collisions between legacy keys.
    fun getLegacyProfileInternalDir(profileId: String): File =
        checkedLibraryPath("profiles/${sanitizeStorageName(profileId)}")

    // Staged layout only. These explicit scopes never consult mutable active-profile
    // providers, sanitize IDs or create directories. Legacy paths above are unchanged.
    fun getGameLibraryCatalogueFile(gameId: String): File =
        checkedLibraryPath("game_libraries/${distinctStorageKey(gameId)}/catalogue.json")

    fun getGameLibraryEntryDir(gameId: String, installationId: String): File =
        checkedLibraryPath("game_libraries/${distinctStorageKey(gameId)}/mods/${distinctStorageKey(installationId)}")

    fun getGameLibraryInstallTransactionDir(gameId: String): File =
        checkedLibraryPath("game_libraries/${distinctStorageKey(gameId)}/install_transaction")

    fun getGameLibraryMigrationFile(gameId: String): File =
        checkedLibraryPath("game_libraries/${distinctStorageKey(gameId)}/migration.json")

    fun getGameLibraryMigrationGuardDirectory(gameId: String): File =
        checkedLibraryPath("game_libraries/${distinctStorageKey(gameId)}/migration_required")

    fun getProfileMembershipFile(gameId: String, profileId: String): File =
        checkedLibraryPath("profile_memberships/${distinctStorageKey(profileId)}/${distinctStorageKey(gameId)}.json")

    fun getProfileOverwriteDirectory(gameId: String, profileId: String): File =
        checkedLibraryPath("profile_overwrites/${distinctStorageKey(profileId)}/${distinctStorageKey(gameId)}")

    fun getProfileOverwriteOwnerFile(gameId: String, profileId: String): File =
        File(getProfileOverwriteDirectory(gameId, profileId), "owner.json").also {
            if (it.absoluteFile != it.canonicalFile) throw IOException("Overwrite owner path contains an alias")
        }

    fun getProfileOverwriteRoleDirectory(
        gameId: String,
        profileId: String,
        role: DeployScope
    ): File {
        val name = when (role) {
            DeployScope.DATA -> "data"
            DeployScope.GAME_ROOT -> "game_root"
            else -> throw IOException("Overwrite requires an explicit Data or Game Root role")
        }
        return File(getProfileOverwriteDirectory(gameId, profileId), name).also {
            if (it.absoluteFile != it.canonicalFile) throw IOException("Overwrite role path contains an alias")
        }
    }

    private fun checkedLibraryPath(relativePath: String): File {
        val root = filesDir.absoluteFile
        val attributes = Files.readAttributes(
            root.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        val path = File(root, relativePath)
        if (!attributes.isDirectory || root != root.canonicalFile || path != path.canonicalFile ||
            !path.toPath().startsWith(root.toPath())) {
            throw IOException("Managed library path is not a canonical owned path")
        }
        return path
    }

    private fun distinctStorageKey(identifier: String): String {
        require(identifier.isNotBlank() && '\u0000' !in identifier && identifier.length <= 96) {
            "Invalid managed storage identifier"
        }
        val bytes = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(identifier))
        require(bytes.remaining() <= 96) { "Managed storage identifier exceeds byte limit" }
        // Reversible, lower-case ASCII avoids both lossy-sanitization and case aliases.
        return buildString {
            while (bytes.hasRemaining()) append((bytes.get().toInt() and 255).toString(16).padStart(2, '0'))
        }
    }

    private fun sanitizeStorageName(value: String): String {
        return value
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
            .trim('_')
            .ifBlank { "default" }
    }
}
