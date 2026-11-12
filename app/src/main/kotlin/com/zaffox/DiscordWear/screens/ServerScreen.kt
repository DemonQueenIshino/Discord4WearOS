package com.zaffox.discordwear.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun ServerScreen(
    onNavigateToDms: () -> Unit,
    onNavigateToChannels: (guildId: String, guildName: String) -> Unit,
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

    val serverRows = remember(guilds) {
        guilds.chunked(3)
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top circular button: Direct Messages (similar to the "recent apps" top icon in Wear OS launcher)
            item {
                CircularDmButton(
                    mentionCount = dmMentionCount,
                    onClick = onNavigateToDms
                )
            }

            item {
                Spacer(modifier = Modifier.height(2.dp))
            }

            if (loading && guilds.isEmpty()) {
                item {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (guilds.isEmpty()) {
                item {
                    Text(
                        "No servers found.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 3-wide circular grid of servers
                items(serverRows.size) { rowIndex ->
                    val row = serverRows[rowIndex]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        row.forEach { guild ->
                            CircularServerButton(
                                guild = guild,
                                imageLoader = imageLoader,
                                mentionCount = guildMentionCounts[guild.id] ?: 0,
                                onClick = { onNavigateToChannels(guild.id, guild.name) }
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Bottom circular button: Settings
            item {
                CircularSettingsButton(onClick = onNavigateToSettings)
            }
        }
    }
}

@Composable
private fun CircularDmButton(
    mentionCount: Int = 0,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(52.dp),
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
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
        modifier = Modifier.size(52.dp),
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
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (mentionCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
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
    }
}

@Composable
private fun CircularSettingsButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
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
