package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry

@Composable
fun ModsPanelDialog(
    mods: List<Mod>,
    modContentIndexes: Map<String, ModContentIndex>,
    onToggleMod: (String) -> Unit,
    onMoveModUp: (String) -> Unit,
    onMoveModDown: (String) -> Unit,
    onDeleteMod: (Mod) -> Unit,
    onViewModFiles: (String) -> Unit,
    onApplyModOrder: (List<String>) -> Unit,
    onOpenOverwriteFolder: () -> Unit,
    onClose: () -> Unit,
    listState: LazyListState
) {
    var orderedMods by remember(mods) {
        mutableStateOf(mods.sortedBy { it.priority })
    }

    fun moveMod(modId: String, direction: Int) {
        val index = orderedMods.indexOfFirst { it.id == modId }
        if (index == -1) return

        val target = (index + direction).coerceIn(0, orderedMods.lastIndex)
        if (target == index) return

        val mutable = orderedMods.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        orderedMods = mutable
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.94f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mods",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenOverwriteFolder) {
                            Text("Overwrite")
                        }

                        Button(onClick = {
                            onApplyModOrder(orderedMods.map { it.id })
                        }) {
                            Text("Save Order")
                        }

                        Button(onClick = onClose) {
                            Text("Close")
                        }
                    }
                }

                Text(
                    text = "Long-press and drag ☰ to reorder. Tap Save Order when done.",
                    style = MaterialTheme.typography.bodySmall
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = orderedMods,
                        key = { _, mod -> mod.id }
                    ) { _, mod ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DragHandle(
                                onStep = { direction ->
                                    moveMod(mod.id, direction)
                                },
                                onDragFinished = {
                                    onApplyModOrder(orderedMods.map { it.id })
                                }
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                CompactModRow(
                                    mod = mod,
                                    contentIndex = modContentIndexes[mod.id],
                                    onToggleMod = onToggleMod,
                                    onMoveModUp = onMoveModUp,
                                    onMoveModDown = onMoveModDown,
                                    onDeleteMod = onDeleteMod,
                                    onViewModFiles = onViewModFiles
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PluginsPanelDialog(
    plugins: List<PluginEntry>,
    onTogglePlugin: (String) -> Unit,
    onMovePluginUp: (String) -> Unit,
    onMovePluginDown: (String) -> Unit,
    onApplyPluginOrder: (List<String>) -> Unit,
    onClose: () -> Unit,
    listState: LazyListState
) {
    var orderedPlugins by remember(plugins) {
        mutableStateOf(plugins.sortedBy { it.priority })
    }

    fun movePlugin(pluginPath: String, direction: Int) {
        val index = orderedPlugins.indexOfFirst { it.normalizedPath == pluginPath }
        if (index == -1) return

        val plugin = orderedPlugins[index]
        if (plugin.locked) return

        val target = (index + direction).coerceIn(0, orderedPlugins.lastIndex)
        if (target == index) return
        if (orderedPlugins[target].locked) return

        val mutable = orderedPlugins.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        orderedPlugins = mutable
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.94f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Plugins",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onApplyPluginOrder(orderedPlugins.map { it.normalizedPath })
                        }) {
                            Text("Save Order")
                        }

                        Button(onClick = onClose) {
                            Text("Close")
                        }
                    }
                }

                Text(
                    text = "Long-press and drag ☰ to reorder unlocked plugins. Official plugins stay pinned.",
                    style = MaterialTheme.typography.bodySmall
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = orderedPlugins,
                        key = { _, plugin -> plugin.normalizedPath }
                    ) { _, plugin ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DragHandle(
                                enabled = !plugin.locked,
                                onStep = { direction ->
                                    movePlugin(plugin.normalizedPath, direction)
                                },
                                onDragFinished = {
                                    onApplyPluginOrder(orderedPlugins.map { it.normalizedPath })
                                }
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                PluginRow(
                                    plugin = plugin,
                                    onTogglePlugin = onTogglePlugin,
                                    onMovePluginUp = onMovePluginUp,
                                    onMovePluginDown = onMovePluginDown
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DragHandle(
    enabled: Boolean = true,
    onStep: (Int) -> Unit,
    onDragFinished: () -> Unit
) {
    val thresholdPx = with(LocalDensity.current) { 56.dp.toPx() }
    var accumulatedY by remember { mutableStateOf(0f) }

    Text(
        text = "☰",
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(8.dp)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                accumulatedY = 0f
                            },
                            onDragEnd = {
                                accumulatedY = 0f
                                onDragFinished()
                            },
                            onDragCancel = {
                                accumulatedY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumulatedY += dragAmount.y

                                while (accumulatedY > thresholdPx) {
                                    onStep(1)
                                    accumulatedY -= thresholdPx
                                }

                                while (accumulatedY < -thresholdPx) {
                                    onStep(-1)
                                    accumulatedY += thresholdPx
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    )
}