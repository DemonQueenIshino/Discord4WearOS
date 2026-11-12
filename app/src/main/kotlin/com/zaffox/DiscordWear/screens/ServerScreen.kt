package com.zaffox.discordwear.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zaffox.discordwear.R
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.api.Guild
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

sealed class ServerListEntry {
    abstract val key: String

    data class SingleServer(val guild: Guild) : ServerListEntry() {
        override val key: String get() = "server_${guild.id}"
    }

    data class FolderEntry(
        val folderId: String,
        val name: String,
        val guilds: List<Guild>
    ) : ServerListEntry() {
        override val key: String get() = "folder_$folderId"
    }
}

/**
 * In-memory runtime holder for server ordering and folders.
 * Resets automatically when the app process restarts.
 */
object RuntimeServerListState {
    val entries = mutableStateListOf<ServerListEntry>()
    var initialized = false

    fun syncWithGuilds(guilds: List<Guild>) {
        if (guilds.isEmpty()) return
        if (!initialized) {
            entries.clear()
            guilds.forEach { entries.add(ServerListEntry.SingleServer(it)) }
            initialized = true
        } else {
            val guildById = guilds.associateBy { it.id }
            val seenIds = mutableSetOf<String>()
            val updated = mutableListOf<ServerListEntry>()

            for (entry in entries) {
                when (entry) {
                    is ServerListEntry.SingleServer -> {
                        val fresh = guildById[entry.guild.id]
                        if (fresh != null) {
                            updated.add(ServerListEntry.SingleServer(fresh))
                            seenIds.add(fresh.id)
                        }
                    }
                    is ServerListEntry.FolderEntry -> {
                        val freshGuilds = entry.guilds.mapNotNull { g ->
                            guildById[g.id]?.also { seenIds.add(it.id) }
                        }
                        if (freshGuilds.isNotEmpty()) {
                            updated.add(entry.copy(guilds = freshGuilds))
                        }
                    }
                }
            }

            guilds.forEach { g ->
                if (g.id !in seenIds) {
                    updated.add(ServerListEntry.SingleServer(g))
                }
            }

            entries.clear()
            entries.addAll(updated)
        }
    }

    fun moveEntry(fromKey: String, toIndex: Int) {
        val fromIdx = entries.indexOfFirst { it.key == fromKey }
        if (fromIdx == -1) return
        val item = entries.removeAt(fromIdx)
        val adjustedIdx = (if (toIndex > fromIdx) toIndex - 1 else toIndex).coerceIn(0, entries.size)
        entries.add(adjustedIdx, item)
    }

    fun mergeIntoTarget(draggedKey: String, targetKey: String) {
        if (draggedKey == targetKey) return
        val fromIdx = entries.indexOfFirst { it.key == draggedKey }
        val toIdx = entries.indexOfFirst { it.key == targetKey }
        if (fromIdx == -1 || toIdx == -1) return

        val dragged = entries[fromIdx]
        val target = entries[toIdx]

        // Only allow merging a single server onto another server or into an existing folder
        if (dragged is ServerListEntry.SingleServer) {
            when (target) {
                is ServerListEntry.SingleServer -> {
                    val newFolder = ServerListEntry.FolderEntry(
                        folderId = UUID.randomUUID().toString(),
                        name = "Folder",
                        guilds = listOf(target.guild, dragged.guild)
                    )
                    entries[toIdx] = newFolder
                    entries.removeAt(fromIdx)
                }
                is ServerListEntry.FolderEntry -> {
                    if (target.guilds.none { it.id == dragged.guild.id }) {
                        entries[toIdx] = target.copy(guilds = target.guilds + dragged.guild)
                    }
                    entries.removeAt(fromIdx)
                }
            }
        } else if (dragged is ServerListEntry.FolderEntry && target is ServerListEntry.FolderEntry) {
            // Merge two folders if a folder is dropped directly onto another folder
            val combined = (target.guilds + dragged.guilds).distinctBy { it.id }
            entries[toIdx] = target.copy(guilds = combined)
            entries.removeAt(fromIdx)
        } else {
            // If folder dropped onto a single server, add that server into the folder or reorder
            val item = entries.removeAt(fromIdx)
            val dest = entries.indexOfFirst { it.key == targetKey }.coerceAtLeast(0)
            entries.add(dest, item)
        }
    }

    fun reorderGuildInFolder(folderId: String, guildId: String, toIndex: Int) {
        val folderIdx = entries.indexOfFirst {
            it is ServerListEntry.FolderEntry && it.folderId == folderId
        }
        if (folderIdx == -1) return
        val folder = entries[folderIdx] as ServerListEntry.FolderEntry
        val fromIdx = folder.guilds.indexOfFirst { it.id == guildId }
        if (fromIdx == -1) return

        val mutableGuilds = folder.guilds.toMutableList()
        val moved = mutableGuilds.removeAt(fromIdx)
        val adjustedIdx = (if (toIndex > fromIdx) toIndex - 1 else toIndex).coerceIn(0, mutableGuilds.size)
        mutableGuilds.add(adjustedIdx, moved)
        entries[folderIdx] = folder.copy(guilds = mutableGuilds)
    }

    fun removeGuildFromFolder(folderId: String, guildId: String) {
        val folderIdx = entries.indexOfFirst {
            it is ServerListEntry.FolderEntry && it.folderId == folderId
        }
        if (folderIdx == -1) return
        val folder = entries[folderIdx] as ServerListEntry.FolderEntry
        val removedGuild = folder.guilds.find { it.id == guildId } ?: return
        val remaining = folder.guilds.filter { it.id != guildId }

        if (remaining.isEmpty()) {
            // Only collapse/delete the folder if it becomes completely empty
            entries.removeAt(folderIdx)
            entries.add(folderIdx, ServerListEntry.SingleServer(removedGuild))
        } else {
            // Folder keeps existing with remaining servers (even with 1 server left)
            entries[folderIdx] = folder.copy(guilds = remaining)
            entries.add(folderIdx + 1, ServerListEntry.SingleServer(removedGuild))
        }
    }
}

private sealed class HexItem {
    abstract val key: String

    data class DirectMessages(val mentionCount: Int) : HexItem() {
        override val key: String = "dms"
    }

    data class Entry(val entry: ServerListEntry, val mentionCount: Int) : HexItem() {
        override val key: String get() = entry.key
    }

    object Settings : HexItem() {
        override val key: String = "settings"
    }
}

private sealed class HexRow {
    data class Center(val item: HexItem) : HexRow()
    data class Pair(val left: HexItem, val right: HexItem?) : HexRow()
}

private sealed class DropAction {
    data class Merge(val targetKey: String) : DropAction()
    data class Reorder(val targetIndex: Int, val anchorCenter: Offset, val insertAfter: Boolean) : DropAction()
}

@Composable
fun ServerScreen(
    onNavigateToDms: () -> Unit,
    onNavigateToChannels: (guildId: String, guildName: String) -> Unit,
    onNavigateToFolder: (folderId: String) -> Unit,
    onNavigateToWelcome: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val imageLoader = remember { ImageLoader.Builder(context).build() }

    LaunchedEffect(Unit) {
        if (!SetupPreferences.isSetupComplete(context)) {
            onNavigateToWelcome()
        }
    }

    if (repo == null) return

    val guilds by repo.guilds.collectAsState()
    val dmChannels by repo.dmChannels.collectAsState()
    val readState by repo.readState.collectAsState()
    val channelGuilds by repo.channelGuilds.collectAsState()
    val showBadges = remember { SetupPreferences.getShowMentionBadges(context) }

    LaunchedEffect(guilds) {
        RuntimeServerListState.syncWithGuilds(guilds)
    }

    val dmIds = remember(dmChannels) { dmChannels.map { it.id }.toSet() }
    val dmMentionCount = remember(readState, dmIds, showBadges) {
        if (!showBadges) 0
        else readState.entries.filter { it.key in dmIds }.sumOf { it.value.mentionCount }
    }

    // Map guildId -> total mention count
    val guildMentionCounts = remember(readState, channelGuilds, showBadges) {
        if (!showBadges) emptyMap()
        else {
            val counts = mutableMapOf<String, Int>()
            readState.forEach { (channelId, state) ->
                if (state.mentionCount > 0) {
                    val guildId = channelGuilds[channelId] ?: return@forEach
                    counts[guildId] = (counts[guildId] ?: 0) + state.mentionCount
                }
            }
            counts
        }
    }

    var loading by remember { mutableStateOf(guilds.isEmpty()) }

    LaunchedEffect(Unit) {
        scope.launch {
            repo.refreshGuilds()
            loading = false
        }
    }

    val runtimeEntries = RuntimeServerListState.entries

    // Build the 1 - 2 - 1 - 2 honeycomb rows starting with DMs at the top center
    val hexRows = remember(runtimeEntries.toList(), dmMentionCount, guildMentionCounts) {
        val items = mutableListOf<HexItem>()
        items.add(HexItem.DirectMessages(dmMentionCount))
        runtimeEntries.forEach { entry ->
            val mentions = when (entry) {
                is ServerListEntry.SingleServer -> guildMentionCounts[entry.guild.id] ?: 0
                is ServerListEntry.FolderEntry -> entry.guilds.sumOf { guildMentionCounts[it.id] ?: 0 }
            }
            items.add(HexItem.Entry(entry, mentions))
        }
        items.add(HexItem.Settings)

        val rows = mutableListOf<HexRow>()
        var index = 0
        var isCenterRow = true
        while (index < items.size) {
            if (isCenterRow) {
                rows.add(HexRow.Center(items[index]))
                index += 1
            } else {
                val left = items[index]
                val right = items.getOrNull(index + 1)
                rows.add(HexRow.Pair(left, right))
                index += if (right != null) 2 else 1
            }
            isCenterRow = !isCenterRow
        }
        rows
    }

    // Drag & Drop state
    val itemCenters = remember { mutableStateMapOf<String, Offset>() }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var draggedEntry by remember { mutableStateOf<ServerListEntry?>(null) }
    var dragPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var activeDropAction by remember { mutableStateOf<DropAction?>(null) }

    val mergeRadiusPx = with(density) { 22.dp.toPx() }
    val reorderRadiusPx = with(density) { 54.dp.toPx() }
    val iconHalfSizePx = with(density) { 25.dp.toPx() }

    fun evaluateDropAction(pointerRoot: Offset, currentDragged: ServerListEntry): DropAction? {
        var closestKey: String? = null
        var closestDist = Float.MAX_VALUE
        var closestCenter = Offset.Zero

        for ((key, center) in itemCenters) {
            if (key == currentDragged.key) continue
            val dist = hypot(pointerRoot.x - center.x, pointerRoot.y - center.y)
            if (dist < closestDist) {
                closestDist = dist
                closestKey = key
                closestCenter = center
            }
        }

        val targetKey = closestKey ?: return null
        val targetIndex = runtimeEntries.indexOfFirst { it.key == targetKey }
        if (targetIndex == -1) return null

        return when {
            closestDist <= mergeRadiusPx && currentDragged is ServerListEntry.SingleServer -> {
                DropAction.Merge(targetKey)
            }
            closestDist <= reorderRadiusPx -> {
                val insertAfter = pointerRoot.y > closestCenter.y + 8f ||
                    (kotlin.math.abs(pointerRoot.y - closestCenter.y) <= 8f && pointerRoot.x > closestCenter.x)
                val insertIdx = if (insertAfter) targetIndex + 1 else targetIndex
                DropAction.Reorder(insertIdx, closestCenter, insertAfter)
            }
            else -> null
        }
    }

    ScreenScaffold(scrollState = listState) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    rootOffset = coords.positionInRoot()
                }
        ) {
            ScalingLazyColumn(
                state = listState,
                userScrollEnabled = draggedEntry == null,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((-14).dp)
            ) {
                if (loading && guilds.isEmpty()) {
                    item {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else {
                    items(hexRows.size) { rowIndex ->
                        when (val row = hexRows[rowIndex]) {
                            is HexRow.Center -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    RenderHexItem(
                                        item = row.item,
                                        imageLoader = imageLoader,
                                        draggedKey = draggedEntry?.key,
                                        mergeTargetKey = (activeDropAction as? DropAction.Merge)?.targetKey,
                                        reorderTargetKey = (activeDropAction as? DropAction.Reorder)?.let {
                                            runtimeEntries.getOrNull(
                                                if (it.insertAfter) it.targetIndex - 1 else it.targetIndex
                                            )?.key
                                        },
                                        onPositionedCenter = { key, center ->
                                            itemCenters[key] = center
                                        },
                                        onStartDrag = { entry, initialCenter ->
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            draggedEntry = entry
                                            dragPositionInRoot = initialCenter
                                            activeDropAction = null
                                        },
                                        onDragBy = { delta ->
                                            dragPositionInRoot += delta
                                            draggedEntry?.let { current ->
                                                val nextAction = evaluateDropAction(dragPositionInRoot, current)
                                                if (nextAction != activeDropAction && nextAction is DropAction.Merge) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                activeDropAction = nextAction
                                            }
                                        },
                                        onEndDrag = {
                                            val current = draggedEntry
                                            val action = activeDropAction
                                            if (current != null && action != null) {
                                                when (action) {
                                                    is DropAction.Merge -> {
                                                        RuntimeServerListState.mergeIntoTarget(current.key, action.targetKey)
                                                    }
                                                    is DropAction.Reorder -> {
                                                        RuntimeServerListState.moveEntry(current.key, action.targetIndex)
                                                    }
                                                }
                                            }
                                            draggedEntry = null
                                            activeDropAction = null
                                        },
                                        onNavigateToDms = onNavigateToDms,
                                        onNavigateToChannels = onNavigateToChannels,
                                        onNavigateToFolder = onNavigateToFolder,
                                        onNavigateToSettings = onNavigateToSettings
                                    )
                                }
                            }

                            is HexRow.Pair -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(66.dp, Alignment.CenterHorizontally),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val reorderHighlightKey = (activeDropAction as? DropAction.Reorder)?.let {
                                            runtimeEntries.getOrNull(
                                                if (it.insertAfter) it.targetIndex - 1 else it.targetIndex
                                            )?.key
                                        }
                                        RenderHexItem(
                                            item = row.left,
                                            imageLoader = imageLoader,
                                            draggedKey = draggedEntry?.key,
                                            mergeTargetKey = (activeDropAction as? DropAction.Merge)?.targetKey,
                                            reorderTargetKey = reorderHighlightKey,
                                            onPositionedCenter = { key, center ->
                                                itemCenters[key] = center
                                            },
                                            onStartDrag = { entry, initialCenter ->
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                draggedEntry = entry
                                                dragPositionInRoot = initialCenter
                                                activeDropAction = null
                                            },
                                            onDragBy = { delta ->
                                                dragPositionInRoot += delta
                                                draggedEntry?.let { current ->
                                                    val nextAction = evaluateDropAction(dragPositionInRoot, current)
                                                    if (nextAction != activeDropAction && nextAction is DropAction.Merge) {
                                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                    activeDropAction = nextAction
                                                }
                                            },
                                            onEndDrag = {
                                                val current = draggedEntry
                                                val action = activeDropAction
                                                if (current != null && action != null) {
                                                    when (action) {
                                                        is DropAction.Merge -> {
                                                            RuntimeServerListState.mergeIntoTarget(current.key, action.targetKey)
                                                        }
                                                        is DropAction.Reorder -> {
                                                            RuntimeServerListState.moveEntry(current.key, action.targetIndex)
                                                        }
                                                    }
                                                }
                                                draggedEntry = null
                                                activeDropAction = null
                                            },
                                            onNavigateToDms = onNavigateToDms,
                                            onNavigateToChannels = onNavigateToChannels,
                                            onNavigateToFolder = onNavigateToFolder,
                                            onNavigateToSettings = onNavigateToSettings
                                        )
                                        if (row.right != null) {
                                            RenderHexItem(
                                                item = row.right,
                                                imageLoader = imageLoader,
                                                draggedKey = draggedEntry?.key,
                                                mergeTargetKey = (activeDropAction as? DropAction.Merge)?.targetKey,
                                                reorderTargetKey = reorderHighlightKey,
                                                onPositionedCenter = { key, center ->
                                                    itemCenters[key] = center
                                                },
                                                onStartDrag = { entry, initialCenter ->
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    draggedEntry = entry
                                                    dragPositionInRoot = initialCenter
                                                    activeDropAction = null
                                                },
                                                onDragBy = { delta ->
                                                    dragPositionInRoot += delta
                                                    draggedEntry?.let { current ->
                                                        val nextAction = evaluateDropAction(dragPositionInRoot, current)
                                                        if (nextAction != activeDropAction && nextAction is DropAction.Merge) {
                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        }
                                                        activeDropAction = nextAction
                                                    }
                                                },
                                                onEndDrag = {
                                                    val current = draggedEntry
                                                    val action = activeDropAction
                                                    if (current != null && action != null) {
                                                        when (action) {
                                                            is DropAction.Merge -> {
                                                                RuntimeServerListState.mergeIntoTarget(current.key, action.targetKey)
                                                            }
                                                            is DropAction.Reorder -> {
                                                                RuntimeServerListState.moveEntry(current.key, action.targetIndex)
                                                            }
                                                        }
                                                    }
                                                    draggedEntry = null
                                                    activeDropAction = null
                                                },
                                                onNavigateToDms = onNavigateToDms,
                                                onNavigateToChannels = onNavigateToChannels,
                                                onNavigateToFolder = onNavigateToFolder,
                                                onNavigateToSettings = onNavigateToSettings
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.size(50.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Floating dragged item overlay
            val activeDragged = draggedEntry
            if (activeDragged != null) {
                val localPos = dragPositionInRoot - rootOffset
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (localPos.x - iconHalfSizePx).roundToInt(),
                                y = (localPos.y - iconHalfSizePx).roundToInt()
                            )
                        }
                        .zIndex(10f)
                        .scale(1.12f)
                        .alpha(0.92f)
                ) {
                    when (activeDragged) {
                        is ServerListEntry.SingleServer -> {
                            CircularServerButton(
                                guild = activeDragged.guild,
                                imageLoader = imageLoader,
                                mentionCount = 0,
                                isMergeTarget = false,
                                isReorderTarget = false,
                                onClick = {}
                            )
                        }
                        is ServerListEntry.FolderEntry -> {
                            CircularFolderButton(
                                folder = activeDragged,
                                imageLoader = imageLoader,
                                mentionCount = 0,
                                isMergeTarget = false,
                                isReorderTarget = false,
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderHexItem(
    item: HexItem,
    imageLoader: ImageLoader,
    draggedKey: String?,
    mergeTargetKey: String?,
    reorderTargetKey: String?,
    onPositionedCenter: (key: String, center: Offset) -> Unit,
    onStartDrag: (entry: ServerListEntry, initialCenter: Offset) -> Unit,
    onDragBy: (delta: Offset) -> Unit,
    onEndDrag: () -> Unit,
    onNavigateToDms: () -> Unit,
    onNavigateToChannels: (guildId: String, guildName: String) -> Unit,
    onNavigateToFolder: (folderId: String) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    when (item) {
        is HexItem.DirectMessages -> {
            CircularDmButton(
                mentionCount = item.mentionCount,
                onClick = onNavigateToDms
            )
        }
        is HexItem.Entry -> {
            val entry = item.entry
            val isBeingDragged = draggedKey == entry.key
            val isMergeTarget = mergeTargetKey == entry.key
            val isReorderTarget = reorderTargetKey == entry.key && !isMergeTarget
            var latestCenter by remember { mutableStateOf(Offset.Zero) }

            Box(
                modifier = Modifier
                    .size(50.dp)
                    .alpha(if (isBeingDragged) 0.28f else 1f)
                    .scale(if (isMergeTarget) 1.15f else 1f)
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInRoot()
                        val center = Offset(
                            pos.x + coords.size.width / 2f,
                            pos.y + coords.size.height / 2f
                        )
                        latestCenter = center
                        onPositionedCenter(entry.key, center)
                    }
                    .pointerInput(entry.key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                onStartDrag(entry, latestCenter)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDragBy(dragAmount)
                            },
                            onDragEnd = {
                                onEndDrag()
                            },
                            onDragCancel = {
                                onEndDrag()
                            }
                        )
                    }
            ) {
                when (entry) {
                    is ServerListEntry.SingleServer -> {
                        CircularServerButton(
                            guild = entry.guild,
                            imageLoader = imageLoader,
                            mentionCount = item.mentionCount,
                            isMergeTarget = isMergeTarget,
                            isReorderTarget = isReorderTarget,
                            onClick = { onNavigateToChannels(entry.guild.id, entry.guild.name) }
                        )
                    }
                    is ServerListEntry.FolderEntry -> {
                        CircularFolderButton(
                            folder = entry,
                            imageLoader = imageLoader,
                            mentionCount = item.mentionCount,
                            isMergeTarget = isMergeTarget,
                            isReorderTarget = isReorderTarget,
                            onClick = { onNavigateToFolder(entry.folderId) }
                        )
                    }
                }
            }
        }
        is HexItem.Settings -> {
            CircularSettingsButton(onClick = onNavigateToSettings)
        }
    }
}

@Composable
private fun CircularDmButton(
    mentionCount: Int = 0,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(50.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xFF5865F2))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_discord_dm),
                contentDescription = "Direct Messages",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }

        if (mentionCount > 0) {
            MentionBadge(mentionCount = mentionCount, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun CircularServerButton(
    guild: Guild,
    imageLoader: ImageLoader,
    mentionCount: Int = 0,
    isMergeTarget: Boolean = false,
    isReorderTarget: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val iconUrl = guild.iconUrl(128)

    val borderModifier = when {
        isMergeTarget -> Modifier.border(2.5.dp, Color(0xFF5865F2), CircleShape)
        isReorderTarget -> Modifier.border(2.dp, Color.White.copy(alpha = 0.75f), CircleShape)
        else -> Modifier
    }

    Box(
        modifier = Modifier.size(50.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(borderModifier)
                .clip(CircleShape)
                .background(Color(0xFF313338))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (iconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(iconUrl).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = guild.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF5865F2), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = guild.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (mentionCount > 0) {
            MentionBadge(mentionCount = mentionCount, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun CircularFolderButton(
    folder: ServerListEntry.FolderEntry,
    imageLoader: ImageLoader,
    mentionCount: Int = 0,
    isMergeTarget: Boolean = false,
    isReorderTarget: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val previewGuilds = folder.guilds.take(4)

    val borderModifier = when {
        isMergeTarget -> Modifier.border(2.5.dp, Color(0xFF5865F2), CircleShape)
        isReorderTarget -> Modifier.border(2.dp, Color.White.copy(alpha = 0.75f), CircleShape)
        else -> Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
    }

    Box(
        modifier = Modifier.size(50.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(borderModifier)
                .clip(CircleShape)
                .background(Color(0xFF2B2D31))
                .clickable(onClick = onClick)
                .padding(7.dp),
            contentAlignment = Alignment.Center
        ) {
            if (folder.guilds.size == 1) {
                MiniGuildAvatar(
                    guild = folder.guilds.first(),
                    imageLoader = imageLoader,
                    context = context,
                    size = 26.dp
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniGuildAvatar(guild = previewGuilds.getOrNull(0), imageLoader = imageLoader, context = context)
                        MiniGuildAvatar(guild = previewGuilds.getOrNull(1), imageLoader = imageLoader, context = context)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniGuildAvatar(guild = previewGuilds.getOrNull(2), imageLoader = imageLoader, context = context)
                        MiniGuildAvatar(guild = previewGuilds.getOrNull(3), imageLoader = imageLoader, context = context)
                    }
                }
            }
        }

        if (mentionCount > 0) {
            MentionBadge(mentionCount = mentionCount, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun MiniGuildAvatar(
    guild: Guild?,
    imageLoader: ImageLoader,
    context: android.content.Context,
    size: androidx.compose.ui.unit.Dp = 15.dp
) {
    if (guild == null) {
        Spacer(modifier = Modifier.size(size))
        return
    }
    val iconUrl = guild.iconUrl(64)
    if (iconUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(iconUrl).crossfade(true).build(),
            imageLoader = imageLoader,
            contentDescription = guild.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(Color(0xFF5865F2), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = guild.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = Color.White,
                fontSize = if (size > 20.dp) 12.sp else 7.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MentionBadge(
    mentionCount: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .background(Color(0xFFF23F43), CircleShape)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (mentionCount > 99) "99+" else mentionCount.toString(),
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CircularSettingsButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.settings),
            contentDescription = "Settings",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
    }
}

private sealed class FolderDropAction {
    object RemoveFromFolder : FolderDropAction()
    data class Reorder(val targetIndex: Int, val highlightGuildId: String) : FolderDropAction()
}

/**
 * Screen displaying the servers inside a folder as a standard vertical list
 * with icons, names, and banners. Supports drag-to-reorder and dragging
 * above the first server to remove from the folder.
 */
@Composable
fun FolderServersScreen(
    folderId: String,
    onNavigateToChannels: (guildId: String, guildName: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val listState = rememberScalingLazyListState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val imageLoader = remember { ImageLoader.Builder(context).build() }

    val readState by repo.readState.collectAsState()
    val channelGuilds by repo.channelGuilds.collectAsState()
    val showBadges = remember { SetupPreferences.getShowMentionBadges(context) }

    val guildMentionCounts = remember(readState, channelGuilds, showBadges) {
        if (!showBadges) emptyMap()
        else {
            val counts = mutableMapOf<String, Int>()
            readState.forEach { (channelId, state) ->
                if (state.mentionCount > 0) {
                    val gId = channelGuilds[channelId] ?: return@forEach
                    counts[gId] = (counts[gId] ?: 0) + state.mentionCount
                }
            }
            counts
        }
    }

    val folderEntry = RuntimeServerListState.entries
        .filterIsInstance<ServerListEntry.FolderEntry>()
        .find { it.folderId == folderId }

    LaunchedEffect(folderEntry) {
        if (folderEntry == null || folderEntry.guilds.isEmpty()) {
            onBack()
        }
    }

    if (folderEntry == null) return

    val itemBounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() } // guildId -> (topY, bottomY) in root
    var headerBottomY by remember { mutableStateOf(0f) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var draggedGuild by remember { mutableStateOf<Guild?>(null) }
    var dragPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var activeFolderDrop by remember { mutableStateOf<FolderDropAction?>(null) }

    val rowHalfHeightPx = with(density) { 26.dp.toPx() }
    val rowWidthPx = with(density) { 150.dp.toPx() }

    fun evaluateFolderDrop(pointerY: Float, currentDragged: Guild): FolderDropAction? {
        val firstGuild = folderEntry.guilds.firstOrNull() ?: return null
        val firstBounds = itemBounds[firstGuild.id]
        val removeThresholdY = if (firstBounds != null) {
            firstBounds.first - with(density) { 6.dp.toPx() }
        } else {
            headerBottomY
        }

        // If dragged above the top of the first server, trigger RemoveFromFolder
        if (pointerY < removeThresholdY) {
            return FolderDropAction.RemoveFromFolder
        }

        var closestGuildId: String? = null
        var closestDist = Float.MAX_VALUE
        var closestCenterY = 0f

        for (g in folderEntry.guilds) {
            if (g.id == currentDragged.id) continue
            val bounds = itemBounds[g.id] ?: continue
            val centerY = (bounds.first + bounds.second) / 2f
            val dist = kotlin.math.abs(pointerY - centerY)
            if (dist < closestDist) {
                closestDist = dist
                closestGuildId = g.id
                closestCenterY = centerY
            }
        }

        val targetId = closestGuildId ?: return null
        val targetIdx = folderEntry.guilds.indexOfFirst { it.id == targetId }
        if (targetIdx == -1) return null

        val insertAfter = pointerY > closestCenterY
        val insertIndex = if (insertAfter) targetIdx + 1 else targetIdx
        return FolderDropAction.Reorder(insertIndex, targetId)
    }

    ScreenScaffold(scrollState = listState) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    rootOffset = coords.positionInRoot()
                }
        ) {
            ScalingLazyColumn(
                state = listState,
                userScrollEnabled = draggedGuild == null,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    val isRemoveActive = activeFolderDrop is FolderDropAction.RemoveFromFolder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isRemoveActive) Color(0xFFF23F43).copy(alpha = 0.28f)
                                else if (draggedGuild != null) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
                                else Color.Transparent
                            )
                            .then(
                                if (isRemoveActive) Modifier.border(1.5.dp, Color(0xFFF23F43), RoundedCornerShape(16.dp))
                                else Modifier
                            )
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                            .onGloballyPositioned { coords ->
                                headerBottomY = coords.positionInRoot().y + coords.size.height
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (draggedGuild != null) "Drag here to remove" else folderEntry.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isRemoveActive) Color(0xFFF23F43) else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                items(folderEntry.guilds.size) { index ->
                    val guild = folderEntry.guilds[index]
                    val isBeingDragged = draggedGuild?.id == guild.id
                    val isReorderTarget = (activeFolderDrop as? FolderDropAction.Reorder)?.highlightGuildId == guild.id
                    var itemCenter by remember { mutableStateOf(Offset.Zero) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isBeingDragged) 0.28f else 1f)
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInRoot()
                                val topY = pos.y
                                val bottomY = pos.y + coords.size.height
                                itemBounds[guild.id] = topY to bottomY
                                itemCenter = Offset(
                                    pos.x + coords.size.width / 2f,
                                    pos.y + coords.size.height / 2f
                                )
                            }
                            .pointerInput(guild.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draggedGuild = guild
                                        dragPositionInRoot = itemCenter
                                        activeFolderDrop = null
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragPositionInRoot += dragAmount
                                        draggedGuild?.let { current ->
                                            val next = evaluateFolderDrop(dragPositionInRoot.y, current)
                                            if (next != activeFolderDrop && next is FolderDropAction.RemoveFromFolder) {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            activeFolderDrop = next
                                        }
                                    },
                                    onDragEnd = {
                                        val current = draggedGuild
                                        val action = activeFolderDrop
                                        if (current != null && action != null) {
                                            when (action) {
                                                is FolderDropAction.RemoveFromFolder -> {
                                                    RuntimeServerListState.removeGuildFromFolder(folderId, current.id)
                                                }
                                                is FolderDropAction.Reorder -> {
                                                    RuntimeServerListState.reorderGuildInFolder(folderId, current.id, action.targetIndex)
                                                }
                                            }
                                        }
                                        draggedGuild = null
                                        activeFolderDrop = null
                                    },
                                    onDragCancel = {
                                        draggedGuild = null
                                        activeFolderDrop = null
                                    }
                                )
                            }
                    ) {
                        FolderServerListButton(
                            guild = guild,
                            imageLoader = imageLoader,
                            mentionCount = guildMentionCounts[guild.id] ?: 0,
                            isReorderTarget = isReorderTarget,
                            onClick = { onNavigateToChannels(guild.id, guild.name) }
                        )
                    }
                }
            }

            // Floating dragged server pill overlay
            val activeDragged = draggedGuild
            if (activeDragged != null) {
                val localPos = dragPositionInRoot - rootOffset
                val isRemoving = activeFolderDrop is FolderDropAction.RemoveFromFolder
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (localPos.x - rowWidthPx / 2f).roundToInt(),
                                y = (localPos.y - rowHalfHeightPx).roundToInt()
                            )
                        }
                        .width(150.dp)
                        .zIndex(10f)
                        .scale(if (isRemoving) 0.92f else 1.04f)
                        .alpha(0.92f)
                ) {
                    FolderServerListButton(
                        guild = activeDragged,
                        imageLoader = imageLoader,
                        mentionCount = 0,
                        isReorderTarget = isRemoving,
                        onClick = {}
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderServerListButton(
    guild: Guild,
    imageLoader: ImageLoader,
    mentionCount: Int = 0,
    isReorderTarget: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bannerUrl = guild.bannerUrl()
    val iconUrl = guild.iconUrl()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(
                if (isReorderTarget) Modifier.border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(26.dp))
                else Modifier
            )
            .clip(RoundedCornerShape(26.dp))
            .clickable(onClick = onClick)
    ) {
        if (bannerUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(bannerUrl).crossfade(true).build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(0.55f), Color.Black.copy(0.30f))
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }

        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (iconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(iconUrl).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0xFF5865F2), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = guild.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        color = Color.White,
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Text(
                text = guild.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (bannerUrl != null) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (mentionCount > 0) {
            MentionBadge(
                mentionCount = mentionCount,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 6.dp)
            )
        }
    }
}
