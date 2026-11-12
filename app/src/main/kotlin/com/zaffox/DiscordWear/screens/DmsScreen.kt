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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.api.*
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

@Composable
fun DmsScreen(
    onNavigateToChatScreen: (channelId: String, channelName: String) -> Unit
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val imageLoader = remember { ImageLoader.Builder(context).build() }

    if (repo == null) return
    val dmChannels by repo.dmChannels.collectAsState()
    val presences by repo.presences.collectAsState()
    val readState by repo.readState.collectAsState()
    val showBadges = remember { SetupPreferences.getShowMentionBadges(context) }
    var loading by remember { mutableStateOf(dmChannels.isEmpty()) }

    LaunchedEffect(Unit) {
        scope.launch {
            repo.refreshDmChannels()
            loading = false
        }
    }

    val sortedDms = remember(dmChannels) {
        dmChannels.sortedByDescending { it.lastMessageId?.toLongOrNull() ?: 0L }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { Text("Direct Messages", style = MaterialTheme.typography.titleMedium) }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (sortedDms.isEmpty()) {
                item { Text("No DMs yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(sortedDms.size) { index ->
                    val dm = sortedDms[index]
                    val recipient = dm.recipients.firstOrNull()
                    val presence = recipient?.let { presences[it.id] }
                    DmButton(
                        dm = dm,
                        recipient = recipient,
                        presence = presence,
                        imageLoader = imageLoader,
                        mentionCount = if (showBadges) readState[dm.id]?.mentionCount ?: 0 else 0,
                        hasUnread = showBadges && dm.lastMessageId != null && run {
                            val rs = readState[dm.id] ?: return@run false
                            dm.lastMessageId > rs.lastMessageId
                        },
                        onClick = { onNavigateToChatScreen(dm.id, dm.displayName) }
                    )
                }
            }
        }
    }
}

private fun OnlineStatus.dotColor(): Color = when (this) {
    OnlineStatus.ONLINE -> Color(0xFF23A55A)
    OnlineStatus.IDLE -> Color(0xFFF0B232)
    OnlineStatus.DND -> Color(0xFFF23F43)
    OnlineStatus.INVISIBLE,
    OnlineStatus.OFFLINE -> Color(0xFF80848E)
}

private fun OnlineStatus.label(): String = when (this) {
    OnlineStatus.ONLINE -> "Online"
    OnlineStatus.IDLE -> "Idle"
    OnlineStatus.DND -> "Do Not Disturb"
    OnlineStatus.INVISIBLE -> "Invisible"
    OnlineStatus.OFFLINE -> "Offline"
}

@Composable
private fun DmButton(
    dm: Channel,
    recipient: DiscordUser?,
    presence: UserPresence?,
    imageLoader: ImageLoader,
    mentionCount: Int = 0,
    hasUnread: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val avatarUrl = recipient?.avatarUrl(64)
    val nameplateUrl = recipient?.nameplateUrl()
    val status = presence?.status ?: OnlineStatus.OFFLINE
    val initial = dm.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        if (nameplateUrl != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context).data(nameplateUrl).crossfade(true).build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                error = { Box(Modifier.matchParentSize()) }
            )
            val scrimAlphaStart = 0.45f
            val scrimAlphaEnd = 0.20f
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(scrimAlphaStart),
                                Color.Black.copy(scrimAlphaEnd)
                            )
                        )
                    )
            )
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }

        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    if (avatarUrl != null) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(context).data(avatarUrl).crossfade(true)
                                .build(),
                            imageLoader = imageLoader,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape),
                            error = {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(Color(0xFF1E1F22), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        initial,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color(0xFF1E1F22), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                initial,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    val decorUrl = recipient?.avatarDecorationUrl()
                    if (decorUrl != null) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(context).data(decorUrl).crossfade(false)
                                .build(),
                            imageLoader = imageLoader,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(52.dp)
                        )
                    }
                    if (mentionCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
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
                    } else if (hasUnread) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(9.dp)
                                .background(Color.White, CircleShape)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .background(Color(0xFF1E1F22), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(status.dotColor(), CircleShape)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = dm.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                val customText = presence?.customStatusText
                val customEmoji = presence?.customStatusEmoji
                if (customText != null || customEmoji != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (customEmoji != null) {
                            if (customEmoji.startsWith("http")) {
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(context).data(customEmoji)
                                        .crossfade(true).build(),
                                    imageLoader = imageLoader,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                            } else {
                                Text(customEmoji, fontSize = 11.sp, lineHeight = 13.sp)
                            }
                        }
                        if (customText != null) {
                            Text(
                                text = customText, style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.75f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, fontSize = 9.sp
                            )
                        }
                    }
                } else {
                    Text(
                        text = status.label(), style = MaterialTheme.typography.labelSmall,
                        color = status.dotColor().copy(alpha = 0.9f), fontSize = 9.sp
                    )
                }
            }
        }
    }
}
