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

    fun sanitizeToken(raw: String?): String? {
        if (raw == null) return null
        var t = raw.trim()
        while ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith("\"") && t.endsWith("\""))) {
            if (t.length <= 2) return null
            t = t.substring(1, t.length - 1).trim()
        }
        return t.takeIf { it.isNotEmpty() }
    }

    private fun getPersistentTokenFiles(context: Context? = null): List<File> {
        val files = mutableListOf<File>()
        runCatching {
            context?.getExternalFilesDir(null)?.let { files.add(File(it, TOKEN_BACKUP_FILENAME)) }
        }
        runCatching {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            files.add(File(downloadDir, TOKEN_BACKUP_FILENAME))
        }
        runCatching {
            val extDir = Environment.getExternalStorageDirectory()
            files.add(File(extDir, TOKEN_BACKUP_FILENAME))
        }
        runCatching {
            files.add(File("/sdcard/Download/$TOKEN_BACKUP_FILENAME"))
        }
        runCatching {
            files.add(File("/sdcard/$TOKEN_BACKUP_FILENAME"))
        }
        runCatching {
            files.add(File("/data/local/tmp/$TOKEN_BACKUP_FILENAME"))
        }
        return files.distinctBy { it.absolutePath }
    }

    fun saveToken(context: Context, token: String) {
        val cleanToken = sanitizeToken(token) ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_TOKEN, cleanToken) }

        // Also persist to shared external storage so it survives full app uninstalls & reinstalls
        getPersistentTokenFiles(context).forEach { file ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(cleanToken)
            }
        }
    }

    fun getToken(context: Context): String? {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefToken = sanitizeToken(sp.getString(KEY_TOKEN, null))
        if (prefToken != null) return prefToken

        // Fallback: Check persistent storage across uninstalls/reinstalls
        for (file in getPersistentTokenFiles(context)) {
            val diskToken = runCatching {
                if (file.exists() && file.canRead()) {
                    sanitizeToken(file.readText())
                } else null
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

        getPersistentTokenFiles(context).forEach { file ->
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
