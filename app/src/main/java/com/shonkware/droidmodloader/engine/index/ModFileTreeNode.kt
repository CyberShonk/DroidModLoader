package com.shonkware.droidmodloader.engine.index

data class ModFileTreeNode(
    val name: String,
    val path: String,
    val isFile: Boolean,
    val status: ModFilePreviewStatus?,
    val children: List<ModFileTreeNode>
)