package com.zaffox.discordwear

import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import java.io.File

object SetupPreferences {
    private const val PREFS_NAME = "discord_wear_prefs"
    private const val KEY_TOKEN = "discord_token"
    private const val KEY_SPOILER_REVEAL_ON_TAP = "spoiler_reveal_on_tap"
    private const val KEY_SHOW_MENTION_BADGES = "show_mention_badges"
    private const val KEY_COMPACT_MODE = "compact_mode"
    private const val TOKEN_BACKUP_FILENAME = ".discord_wear_token"

    private fun getPersistentTokenFiles(): List<File> {
        val files = mutableListOf<File>()
        runCatching {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()
            files.add(File(downloadDir, TOKEN_BACKUP_FILENAME))
        }
        runCatching {
            val extDir = Environment.getExternalStorageDirectory()
            files.add(File(extDir, TOKEN_BACKUP_FILENAME))
        }
        return files
    }

    fun saveToken(context: Context, token: String) {
        val cleanToken = token.trim()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_TOKEN, cleanToken) }

        // Also persist to shared external storage so it survives full app uninstalls & reinstalls
        getPersistentTokenFiles().forEach { file ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(cleanToken)
            }
        }
    }

    fun getToken(context: Context): String? {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefToken = sp.getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (prefToken != null) return prefToken

        // Fallback: Check persistent storage across uninstalls/reinstalls
        for (file in getPersistentTokenFiles()) {
            val diskToken = runCatching {
                if (file.exists()) file.readText().trim().takeIf { it.isNotEmpty() } else null
            }.getOrNull()

            if (diskToken != null) {
                // Restore into SharedPreferences so subsequent lookups are instant
                sp.edit { putString(KEY_TOKEN, diskToken) }
                return diskToken
            }
        }

        return null
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_TOKEN) }

        getPersistentTokenFiles().forEach { file ->
            runCatching {
                if (file.exists()) file.delete()
            }
        }
    }

    fun getSpoilerRevealOnTap(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPOILER_REVEAL_ON_TAP, true)

    fun setSpoilerRevealOnTap(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SPOILER_REVEAL_ON_TAP, value) }
    }

    fun getShowMentionBadges(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_MENTION_BADGES, true)

    fun setShowMentionBadges(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SHOW_MENTION_BADGES, value) }
    }

    fun getCompactMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPACT_MODE, false)

    fun setCompactMode(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_COMPACT_MODE, value) }
    }

    fun isSetupComplete(context: Context): Boolean = getToken(context) != null

    fun clearDiscordCache(context: Context) {
        val cache = context.getSharedPreferences("discord_wear_cache", Context.MODE_PRIVATE)
        cache.edit { clear() }
    }

    fun clearAll(context: Context) {
        clearToken(context)
        clearDiscordCache(context)
    }
}
