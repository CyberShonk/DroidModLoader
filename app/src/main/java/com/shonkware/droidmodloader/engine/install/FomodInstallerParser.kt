package com.shonkware.droidmodloader.engine.install

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FomodInstallerParser {

    fun parse(contentRoot: File, moduleConfigFile: File, modName: String): InstallerPlan {
        val warnings = mutableListOf<String>()
        warnings.add("FOMOD installer detected. Basic XML installer support is enabled; complex conditions/scripts are not fully supported yet.")

        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(moduleConfigFile)

        document.documentElement.normalize()

        val groups = mutableListOf<InstallerGroup>()

        val groupNodes = document.getElementsByTagName("group")
        for (i in 0 until groupNodes.length) {
            val groupElement = groupNodes.item(i) as? Element ?: continue

            val groupName = groupElement.getAttribute("name").ifBlank { "Options ${i + 1}" }
            val groupType = parseGroupType(groupElement.getAttribute("type"))

            val pluginNodes = groupElement.getElementsByTagName("plugin")
            val options = mutableListOf<InstallerOption>()

            for (j in 0 until pluginNodes.length) {
                val pluginElement = pluginNodes.item(j) as? Element ?: continue
                val optionName = pluginElement.getAttribute("name").ifBlank { "Option ${j + 1}" }

                val description = pluginElement.getDirectChildText("description")
                val typeName = pluginElement
                    .getElementsByTagName("type")
                    .item(0)
                    ?.let { it as? Element }
                    ?.getAttribute("name")
                    .orEmpty()

                val required = typeName.equals("Required", ignoreCase = true)
                val recommended = typeName.equals("Recommended", ignoreCase = true)

                val fileMappings = parseFirstFileMapping(pluginElement)

                if (fileMappings == null) {
                    warnings.add("FOMOD option '$optionName' has no directly supported file/folder mapping.")
                    continue
                }

                options.add(
                    InstallerOption(
                        id = "fomod_${i}_${j}",
                        name = optionName,
                        description = description,
                        sourcePath = fileMappings.first,
                        destinationPath = fileMappings.second,
                        required = required,
                        selectedByDefault = required || recommended
                    )
                )
            }

            if (options.isNotEmpty()) {
                groups.add(
                    InstallerGroup(
                        id = "fomod_group_$i",
                        name = groupName,
                        type = groupType,
                        options = options
                    )
                )
            }
        }

        if (groups.isEmpty()) {
            warnings.add("FOMOD parser found no supported options. Falling back to simple install root.")
            return InstallerPlan(
                installerType = InstallerType.SIMPLE,
                displayName = modName,
                rootPath = contentRoot.absolutePath,
                groups = listOf(
                    InstallerGroup(
                        id = "fallback",
                        name = "Fallback Install",
                        type = InstallerGroupType.SELECT_ANY,
                        options = listOf(
                            InstallerOption(
                                id = "fallback_all",
                                name = "Install detected files",
                                sourcePath = ".",
                                destinationPath = "",
                                required = true,
                                selectedByDefault = true
                            )
                        )
                    )
                ),
                warnings = warnings
            )
        }

        return InstallerPlan(
            installerType = InstallerType.FOMOD,
            displayName = modName,
            rootPath = contentRoot.absolutePath,
            groups = groups,
            warnings = warnings
        )
    }

    private fun parseGroupType(type: String): InstallerGroupType {
        return when (type.lowercase()) {
            "selectexactlyone" -> InstallerGroupType.SELECT_EXACTLY_ONE
            "selectatmostone" -> InstallerGroupType.SELECT_AT_MOST_ONE
            "selectatleastone" -> InstallerGroupType.SELECT_AT_LEAST_ONE
            else -> InstallerGroupType.SELECT_ANY
        }
    }

    private fun parseFirstFileMapping(pluginElement: Element): Pair<String, String>? {
        val fileNodes = pluginElement.getElementsByTagName("file")
        if (fileNodes.length > 0) {
            val file = fileNodes.item(0) as? Element ?: return null
            val source = file.getAttribute("source").ifBlank { return null }
            val destination = file.getAttribute("destination")
            return source to destination
        }

        val folderNodes = pluginElement.getElementsByTagName("folder")
        if (folderNodes.length > 0) {
            val folder = folderNodes.item(0) as? Element ?: return null
            val source = folder.getAttribute("source").ifBlank { return null }
            val destination = folder.getAttribute("destination")
            return source to destination
        }

        return null
    }

    private fun Element.getDirectChildText(tagName: String): String {
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName.equals(tagName, ignoreCase = true)) {
                return node.textContent?.trim().orEmpty()
            }
        }
        return ""
    }
}