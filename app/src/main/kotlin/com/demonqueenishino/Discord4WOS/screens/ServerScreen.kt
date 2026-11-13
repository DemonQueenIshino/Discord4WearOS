package com.demonqueenishino.Discord4WOS.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.demonqueenishino.Discord4WOS.R
import com.demonqueenishino.Discord4WOS.SetupPreferences
import com.demonqueenishino.Discord4WOS.api.Guild
import com.demonqueenishino.Discord4WOS.api.RawGuildFolder
import com.demonqueenishino.Discord4WOS.discordApp
import kotlinx.coroutines.launch
import java.util.UUID

sealed class ServerListEntry {
    abstract val key: String

    data class SingleServer(val guild: Guild) : ServerListEntry() {
        override val key: String get() = "server_${guild.id}"
    }

    data class FolderEntry(
        val folderId: String,
        val name: String,
        val color: Int?,
        val guilds: List<Guild>
    ) : ServerListEntry() {
        override val key: String get() = "folder_$folderId"
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
    val imageLoader = remember { ImageLoader.Builder(context).build() }

    LaunchedEffect(Unit) {
        if (!SetupPreferences.isSetupComplete(context)) {
            onNavigateToWelcome()
        }
    }

    if (repo == null) return

    val guilds by repo.guilds.collectAsState()
    val guildFolders by repo.guildFolders.collectAsState()
    val dmChannels by repo.dmChannels.collectAsState()
    val readState by repo.readState.collectAsState()
    val channelGuilds by repo.channelGuilds.collectAsState()
    val showBadges = remember { SetupPreferences.getShowMentionBadges(context) }

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

    // Convert raw guilds and user account guild_folders into structured server list entries
    val serverEntries = remember(guilds, guildFolders) {
        if (guilds.isEmpty()) return@remember emptyList<ServerListEntry>()
        val guildMap = guilds.associateBy { it.id }
        val placedGuildIds = mutableSetOf<String>()
        val result = mutableListOf<ServerListEntry>()

        if (guildFolders.isNotEmpty()) {
            guildFolders.forEach { rawFolder ->
                val folderGuilds = rawFolder.guildIds.mapNotNull { guildMap[it] }
                if (folderGuilds.isNotEmpty()) {
                    if (rawFolder.isFolder) {
                        result.add(
                            ServerListEntry.FolderEntry(
                                folderId = rawFolder.id ?: UUID.randomUUID().toString(),
                                name = rawFolder.name?.takeIf { it.isNotBlank() } ?: "Folder",
                                color = rawFolder.color,
                                guilds = folderGuilds
                            )
                        )
                    } else {
                        result.add(ServerListEntry.SingleServer(folderGuilds.first()))
                    }
                    folderGuilds.forEach { placedGuildIds.add(it.id) }
                }
            }
        }

        // Add any remaining servers not listed in folders (or if no folders exist)
        guilds.forEach { guild ->
            if (guild.id !in placedGuildIds) {
                result.add(ServerListEntry.SingleServer(guild))
            }
        }

        result
    }

    // Build the 1 - 2 - 1 - 2 honeycomb rows starting with DMs at the top center
    val hexRows = remember(serverEntries, dmMentionCount, guildMentionCounts) {
        val items = mutableListOf<HexItem>()
        items.add(HexItem.DirectMessages(dmMentionCount))
        serverEntries.forEach { entry ->
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

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((-24).dp)
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
                                    .height(60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                RenderHexItem(
                                    item = row.item,
                                    imageLoader = imageLoader,
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
                                    .height(60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(60.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RenderHexItem(
                                        item = row.left,
                                        imageLoader = imageLoader,
                                        onNavigateToDms = onNavigateToDms,
                                        onNavigateToChannels = onNavigateToChannels,
                                        onNavigateToFolder = onNavigateToFolder,
                                        onNavigateToSettings = onNavigateToSettings
                                    )
                                    if (row.right != null) {
                                        RenderHexItem(
                                            item = row.right,
                                            imageLoader = imageLoader,
                                            onNavigateToDms = onNavigateToDms,
                                            onNavigateToChannels = onNavigateToChannels,
                                            onNavigateToFolder = onNavigateToFolder,
                                            onNavigateToSettings = onNavigateToSettings
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(60.dp))
                                    }
                                }
                            }
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
            when (val entry = item.entry) {
                is ServerListEntry.SingleServer -> {
                    CircularServerButton(
                        guild = entry.guild,
                        imageLoader = imageLoader,
                        mentionCount = item.mentionCount,
                        onClick = { onNavigateToChannels(entry.guild.id, entry.guild.name) }
                    )
                }
                is ServerListEntry.FolderEntry -> {
                    CircularFolderButton(
                        folder = entry,
                        imageLoader = imageLoader,
                        mentionCount = item.mentionCount,
                        onClick = { onNavigateToFolder(entry.folderId) }
                    )
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
        modifier = Modifier.size(60.dp),
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
                modifier = Modifier.size(32.dp)
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
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val iconUrl = guild.iconUrl(128)

    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                        fontSize = 22.sp,
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
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val previewGuilds = folder.guilds.take(4)

    val folderBorderColor = if (folder.color != null && folder.color != 0) {
        Color(folder.color or 0xFF000000.toInt()).copy(alpha = 0.65f)
    } else {
        Color.White.copy(alpha = 0.16f)
    }

    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.5.dp, folderBorderColor, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFF2B2D31))
                .clickable(onClick = onClick)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (folder.guilds.size == 1) {
                MiniGuildAvatar(
                    guild = folder.guilds.first(),
                    imageLoader = imageLoader,
                    context = context,
                    size = 32.dp
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniGuildAvatar(guild = previewGuilds.getOrNull(0), imageLoader = imageLoader, context = context, size = 18.dp)
                        MiniGuildAvatar(guild = previewGuilds.getOrNull(1), imageLoader = imageLoader, context = context, size = 18.dp)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniGuildAvatar(guild = previewGuilds.getOrNull(2), imageLoader = imageLoader, context = context, size = 18.dp)
                        MiniGuildAvatar(guild = previewGuilds.getOrNull(3), imageLoader = imageLoader, context = context, size = 18.dp)
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
    size: androidx.compose.ui.unit.Dp = 18.dp
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
                fontSize = if (size > 20.dp) 14.sp else 8.sp,
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
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .background(Color(0xFFF23F43), CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (mentionCount > 99) "99+" else mentionCount.toString(),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CircularSettingsButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.settings),
            contentDescription = "Settings",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp)
        )
    }
}

/**
 * Screen displaying the servers inside a folder as a standard vertical list
 * with icons, names, and banners matching the official Discord folder experience.
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
    val imageLoader = remember { ImageLoader.Builder(context).build() }

    val guilds by repo.guilds.collectAsState()
    val guildFolders by repo.guildFolders.collectAsState()
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

    val folderEntry = remember(guilds, guildFolders, folderId) {
        val guildMap = guilds.associateBy { it.id }
        val raw = guildFolders.find { it.id == folderId }
        if (raw != null) {
            val fGuilds = raw.guildIds.mapNotNull { guildMap[it] }
            ServerListEntry.FolderEntry(
                folderId = raw.id ?: folderId,
                name = raw.name?.takeIf { it.isNotBlank() } ?: "Folder",
                color = raw.color,
                guilds = fGuilds
            )
        } else null
    }

    LaunchedEffect(folderEntry) {
        if (folderEntry == null || folderEntry.guilds.isEmpty()) {
            onBack()
        }
    }

    if (folderEntry == null) return

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Text(
                    text = folderEntry.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp)
                )
            }

            items(folderEntry.guilds.size) { index ->
                val guild = folderEntry.guilds[index]
                FolderServerListButton(
                    guild = guild,
                    imageLoader = imageLoader,
                    mentionCount = guildMentionCounts[guild.id] ?: 0,
                    onClick = { onNavigateToChannels(guild.id, guild.name) }
                )
            }
        }
    }
}

@Composable
private fun FolderServerListButton(
    guild: Guild,
    imageLoader: ImageLoader,
    mentionCount: Int = 0,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bannerUrl = guild.bannerUrl()
    val iconUrl = guild.iconUrl()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
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
