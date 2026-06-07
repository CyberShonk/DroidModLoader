package com.shonkware.droidmodloader.engine.install

object ModDisplayNameNormalizer {

    private val archiveExtensions = listOf(
        ".zip",
        ".7z",
        ".rar",
        ".fomod"
    )

    fun cleanDisplayName(
        sourceArchiveName: String?,
        fallbackFolderName: String
    ): String {
        val source = sourceArchiveName
            ?.takeIf { it.isNotBlank() }
            ?: fallbackFolderName

        var name = source.trim()

        name = removeArchiveExtension(name)
        name = name.replace(Regex("""-\d+(?:-\d+){1,}-\d{8,}$"""), "")
        name = name.replace(Regex("""-\d+(?:-\d+)+$"""), "")
        name = removeLeadingTimestampPrefix(name)
        name = name.replace('_', ' ')
        name = name.replace(Regex("""\s+"""), " ").trim()

        name = removeCommonNexusNumericSuffix(name)
        name = fixCommonVersionSeparators(name)
        name = cleanKnownNames(name)

        return name
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { fallbackFolderName }
    }

    private fun removeArchiveExtension(value: String): String {
        val lower = value.lowercase()

        val extension = archiveExtensions.firstOrNull { lower.endsWith(it) }
            ?: return value

        return value.dropLast(extension.length)
    }

    private fun removeLeadingTimestampPrefix(value: String): String {
        return value.replace(
            Regex("""^\d{10,}[\s_-]+"""),
            ""
        )
    }

    private fun removeCommonNexusNumericSuffix(value: String): String {
        var name = value.trim()

        // Common Nexus-ish pattern:
        // Enhanced Blood Textures-60-4-0-1740001234
        // Updated Unofficial Fallout 3 Patch-19122-3-5-2-1749195750
        //
        // Remove a trailing cluster that starts with a mod/file id and ends
        // with a long timestamp-like id.
        name = name.replace(
            Regex("""[-\s]+(?:version[-\s]+)?\d{3,}(?:[-\s]+\d+){1,8}[-\s]+\d{9,}$""", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        // Common:
        // Some Mod version-85289-1-0-1709505165
        name = name.replace(
            Regex("""[-\s]+version[-\s]+\d{3,}(?:[-\s]+\d+){0,8}$""", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        // Common:
        // Some Mod-12345
        // Be conservative: only remove one trailing numeric chunk if the name
        // already has several words and the number looks like a Nexus id.
        name = name.replace(
            Regex("""^(.{8,}?\D)[-\s]+\d{5,}$"""),
            "$1"
        ).trim()

        return name
    }

    private fun fixCommonVersionSeparators(value: String): String {
        var name = value

        // SKSE 1 07 03 -> SKSE 1.07.03
        name = name.replace(
            Regex("""\b(SKSE|NVSE|FOSE|OBSE|F4SE)\s+(\d+)\s+(\d+)\s+(\d+)\b""", RegexOption.IGNORE_CASE)
        ) { match ->
            val label = match.groupValues[1].uppercase()
            val major = match.groupValues[2]
            val minor = match.groupValues[3].padStart(2, '0')
            val patch = match.groupValues[4].padStart(2, '0')
            "$label $major.$minor.$patch"
        }

        return name
    }

    private fun cleanKnownNames(value: String): String {
        val trimmed = value.trim()

        return when {
            trimmed.equals("skse", ignoreCase = true) -> "SKSE"
            trimmed.equals("nvse", ignoreCase = true) -> "NVSE"
            trimmed.equals("fose", ignoreCase = true) -> "FOSE"
            trimmed.equals("obse", ignoreCase = true) -> "OBSE"
            trimmed.equals("f4se", ignoreCase = true) -> "F4SE"

            trimmed.startsWith("skse ", ignoreCase = true) ->
                "SKSE " + trimmed.substringAfter(" ")

            trimmed.startsWith("nvse ", ignoreCase = true) ->
                "NVSE " + trimmed.substringAfter(" ")

            trimmed.startsWith("fose ", ignoreCase = true) ->
                "FOSE " + trimmed.substringAfter(" ")

            trimmed.startsWith("obse ", ignoreCase = true) ->
                "OBSE " + trimmed.substringAfter(" ")

            trimmed.startsWith("f4se ", ignoreCase = true) ->
                "F4SE " + trimmed.substringAfter(" ")

            else -> trimmed
        }
    }
}