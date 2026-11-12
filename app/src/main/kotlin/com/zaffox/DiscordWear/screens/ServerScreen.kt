package com.zaffox.discordwear.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
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

    // Map guildId -> total mention count, using channelGuilds for attribution
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

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = "Discord",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // "First server" entry: Direct Messages button
            item {
                DirectMessagesEntryButton(
                    mentionCount = dmMentionCount,
                    onClick = onNavigateToDms
                )
            }

            // Subtle divider between Direct Messages and the server list (like desktop/mobile Discord)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(2.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
            }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (guilds.isEmpty()) {
                item {
                    Text(
                        "No servers found.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(guilds.size) { index ->
                    val guild = guilds[index]
                    ServerButton(
                        guild = guild,
                        imageLoader = imageLoader,
                        mentionCount = guildMentionCounts[guild.id] ?: 0,
                        onClick = { onNavigateToChannels(guild.id, guild.name) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            item {
                FilledIconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .height(40.dp)
                        .width(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.settings),
                        contentDescription = "Settings"
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectMessagesEntryButton(
    mentionCount: Int = 0,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color(0xFF5865F2), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_discord_dm),
                    contentDescription = "Direct Messages",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            Text(
                text = "Direct Messages",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (mentionCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 6.dp)
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
private fun ServerButton(
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 6.dp)
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
