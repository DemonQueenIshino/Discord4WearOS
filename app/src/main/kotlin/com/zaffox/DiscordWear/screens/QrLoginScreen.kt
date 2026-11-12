package com.zaffox.discordwear.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.zaffox.discordwear.RemoteAuthClient
import com.zaffox.discordwear.RemoteAuthState
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.api.DiscordRestClient
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

@Composable
fun QrLoginScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RemoteAuthState>(RemoteAuthState.Connecting) }
    var client by remember { mutableStateOf<RemoteAuthClient?>(null) }

    fun startQrSession() {
        client?.disconnect()
        state = RemoteAuthState.Connecting
        val c = RemoteAuthClient(
            onStateChange = { newState -> state = newState },
            onStatusUpdate = {},
            onTokenReceived = { token ->
                scope.launch {
                    state = RemoteAuthState.Connecting
                    val check = DiscordRestClient(token).getCurrentUser()
                    if (check.isSuccess) {
                        SetupPreferences.saveToken(context, token)
                        context.discordApp.initRepository(token)
                        onSetupComplete()
                    } else {
                        state = RemoteAuthState.Error("Invalid token received")
                    }
                }
            }
        )
        client = c
        c.connect()
    }

    LaunchedEffect(Unit) {
        val existingToken = SetupPreferences.getToken(context)
        if (existingToken != null) {
            context.discordApp.initRepository(existingToken)
            onSetupComplete()
            return@LaunchedEffect
        }
        startQrSession()
    }

    val activity = context as? android.app.Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            client?.disconnect()
        }
    }

    ScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 20.dp, bottom = 14.dp, start = 16.dp, end = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                modifier = Modifier.fillMaxWidth()
            ) {
                when (val s = state) {
                    is RemoteAuthState.Connecting -> {
                        Box(
                            modifier = Modifier.size(92.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                        Text(
                            "Scan with Discord app to log in",
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is RemoteAuthState.WaitingForScan -> {
                        QrCodeImage(
                            content = "https://discord.com/ra/${s.fingerprint}",
                            modifier = Modifier
                                .size(92.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(5.dp)
                        )
                        Text(
                            "Scan with Discord app to log in",
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is RemoteAuthState.UserScanned -> {
                        val avatarUrl = if (s.avatarHash != "0")
                            "https://cdn.discordapp.com/avatars/${s.userId}/${s.avatarHash}.png?size=80"
                        else null
                        if (avatarUrl != null) {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = "Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    s.username.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                        Text(
                            s.username,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Confirm on your phone",
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center
                        )
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }

                    is RemoteAuthState.CaptchaRequired -> {
                        Text(
                            "Captcha Required",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Check VPN/Proxy and try again.",
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        Button(
                            onClick = { startQrSession() },
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(32.dp),
                            colors = ButtonDefaults.filledTonalButtonColors()
                        ) { Text("Retry") }
                    }

                    is RemoteAuthState.Canceled -> {
                        Text(
                            "Login Canceled",
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { startQrSession() },
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(32.dp),
                            colors = ButtonDefaults.filledTonalButtonColors()
                        ) { Text("Retry") }
                    }

                    is RemoteAuthState.Error -> {
                        Text(
                            s.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Button(
                            onClick = { startQrSession() },
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(32.dp),
                            colors = ButtonDefaults.filledTonalButtonColors()
                        ) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { generateQrBitmap(content, 256) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code",
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 0)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size)
        bmp.setPixel(
            x,
            y,
            if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )
    bmp
}.getOrNull()
