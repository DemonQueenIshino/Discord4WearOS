package com.zaffox.discordwear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.zaffox.discordwear.screens.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    var activeChannelId: String? = null
    private var hasPromptedStoragePermission = false
    private var isLoggedInState by mutableStateOf(false)

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!isLoggedInState) {
            val token = SetupPreferences.getToken(this)
            if (token != null) {
                discordApp.initRepository(token)
                isLoggedInState = true
            }
        }
    }

    private fun checkAndRequestStoragePermission() {
        if (hasPromptedStoragePermission) return
        hasPromptedStoragePermission = true

        // Request standard runtime permissions (triggers native Wear OS popup dialog)
        val permsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (permsToRequest.isNotEmpty()) {
            runCatching { storagePermissionLauncher.launch(permsToRequest.toTypedArray()) }
        }

        // Also attempt All Files Access screen if supported by the watch ROM
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val pkgUri = Uri.parse("package:$packageName")
            val pkgIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)
            if (pkgIntent.resolveActivity(packageManager) != null) {
                runCatching { startActivity(pkgIntent) }
            } else {
                val allIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                if (allIntent.resolveActivity(packageManager) != null) {
                    runCatching { startActivity(allIntent) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestStoragePermission()

        val token = SetupPreferences.getToken(this)
        if (token != null) {
            discordApp.initRepository(token)
            isLoggedInState = true
        }

        setContent {
            MaterialTheme {
                val repo = discordApp.repository
                val isTokenInvalid by repo?.isTokenInvalid?.collectAsState()
                    ?: remember { mutableStateOf(false) }
                var showInvalidTokenPopup by remember { mutableStateOf(false) }

                androidx.compose.runtime.LaunchedEffect(isTokenInvalid) {
                    if (isTokenInvalid) {
                        SetupPreferences.clearToken(this@MainActivity)
                        discordApp.clearRepository()
                        isLoggedInState = false
                        showInvalidTokenPopup = true
                    }
                }

                AppScaffold(timeText = { TimeText() }) {
                    val navController = rememberSwipeDismissableNavController()

                    androidx.compose.runtime.LaunchedEffect(isLoggedInState) {
                        if (isLoggedInState && navController.currentDestination?.route == "Welcome") {
                            navController.navigate("home") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }

                    SwipeDismissableNavHost(
                        navController = navController,
                        startDestination = if (isLoggedInState) "home" else "Welcome"
                    ) {

                        composable("home") {
                            activeChannelId = null
                            ServerScreen(
                                onNavigateToDms = { navController.navigate("DMs") },
                                onNavigateToChannels = { gId, gName ->
                                    navController.navigate("ServerChannels/$gId/$gName")
                                },
                                onNavigateToWelcome = { navController.navigate("Welcome") },
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }

                        composable("Welcome") {
                            QrLoginScreen(
                                onSetupComplete = {
                                    isLoggedInState = true
                                    navController.navigate("home") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                onLogOut = {
                                    isLoggedInState = false
                                    navController.navigate("Welcome") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("chatscreen/{channelId}/{channelName}/{guildId}") { back ->
                            val channelId =
                                back.arguments?.getString("channelId") ?: return@composable
                            val channelName = back.arguments?.getString("channelName") ?: channelId
                            val guildIdArg = back.arguments?.getString("guildId")
                            val guildId = if (guildIdArg == "dm") null else guildIdArg
                            activeChannelId = channelId

                            ChatScreen(
                                channelId = channelId,
                                channelName = channelName,
                                guildId = guildId,
                                onNavigateToProfile = { userId, user ->
                                    val encodedName = java.net.URLEncoder.encode(
                                        user?.displayName ?: userId,
                                        "UTF-8"
                                    )
                                    navController.navigate("userprofile/$userId/$encodedName/${guildIdArg ?: "dm"}")
                                },
                                onNavigateToThread = { threadId, threadName ->
                                    val encodedName =
                                        java.net.URLEncoder.encode(threadName, "UTF-8")
                                    navController.navigate("thread/$threadId/$encodedName/${guildIdArg ?: "dm"}")
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("thread/{threadId}/{threadName}/{guildId}") { back ->
                            val threadId =
                                back.arguments?.getString("threadId") ?: return@composable
                            val threadName = back.arguments?.getString("threadName")
                                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: threadId
                            val guildIdArg = back.arguments?.getString("guildId")
                            val guildId = if (guildIdArg == "dm") null else guildIdArg
                            activeChannelId = threadId
                            ThreadScreen(
                                threadId = threadId,
                                threadName = threadName,
                                guildId = guildId,
                                onNavigateToProfile = { userId, user ->
                                    val encodedName = java.net.URLEncoder.encode(
                                        user?.displayName ?: userId,
                                        "UTF-8"
                                    )
                                    navController.navigate("userprofile/$userId/$encodedName/${guildIdArg ?: "dm"}")
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("userprofile/{userId}/{displayName}/{guildId}") { back ->
                            val userId = back.arguments?.getString("userId") ?: return@composable
                            val guildIdArg = back.arguments?.getString("guildId")
                            val guildId = if (guildIdArg == "dm") null else guildIdArg
                            UserProfileScreen(
                                userId = userId,
                                guildId = guildId,
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("ServerChannels/{guildId}/{guildName}") { back ->
                            val guildId = back.arguments?.getString("guildId") ?: return@composable
                            val guildName = back.arguments?.getString("guildName") ?: guildId
                            activeChannelId = null
                            ServerChannels(
                                guildId = guildId,
                                guildName = guildName,
                                onNavigateToChatScreen = { chId, chName ->
                                    navController.navigate("chatscreen/$chId/$chName/$guildId")
                                }
                            )
                        }

                        composable("DMs") {
                            activeChannelId = null
                            DmsScreen(onNavigateToChatScreen = { chId, chName ->
                                navController.navigate("chatscreen/$chId/$chName/dm")
                            })
                        }
                    }

                    if (showInvalidTokenPopup) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            ScreenScaffold(scrollState = rememberScalingLazyListState()) {
                                ScalingLazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    item {
                                        Text(
                                            "Invalid Token",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.error,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp)
                                        )
                                    }
                                    item {
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    item {
                                        Text(
                                            "Token is invalid or has expired. \n You have been logged out.",
                                            style = MaterialTheme.typography.bodySmall,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp)
                                        )
                                    }
                                    item {
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    item {
                                        Button(
                                            onClick = {
                                                showInvalidTokenPopup = false
                                                navController.navigate("Welcome") {
                                                    popUpTo(0) { inclusive = true }
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth(0.85f)
                                                .height(36.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors()
                                        ) {
                                            Text("OK")
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

    override fun onResume() {
        super.onResume()
        if (!isLoggedInState) {
            val token = SetupPreferences.getToken(this)
            if (token != null) {
                discordApp.initRepository(token)
                isLoggedInState = true
            }
        }
        discordApp.repository?.refreshOnResume(activeChannelId)
    }
}
