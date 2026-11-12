package com.demonqueenishino.Discord4WOS

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.edit
import java.io.File

object SetupPreferences {
    private const val PREFS_NAME = "discord_wear_prefs"
    private const val KEY_TOKEN = "discord_token"
    private const val KEY_SPOILER_REVEAL_ON_TAP = "spoiler_reveal_on_tap"
    private const val KEY_SHOW_MENTION_BADGES = "show_mention_badges"
    private const val KEY_COMPACT_MODE = "compact_mode"
    private const val TOKEN_BACKUP_FILENAME = ".discord_wear_token"
    private const val MEDIASTORE_TOKEN_NAME = "discord_wear_session.txt"

    fun sanitizeToken(raw: String?): String? {
        if (raw == null) return null
        var t = raw.trim()
        while ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith("\"") && t.endsWith("\""))) {
            if (t.length <= 2) return null
            t = t.substring(1, t.length - 1).trim()
        }
        return t.takeIf { it.isNotEmpty() }
    }

    private fun saveToMediaStore(context: Context, cleanToken: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // Remove existing owned entries first
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(MEDIASTORE_TOKEN_NAME)
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, MEDIASTORE_TOKEN_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                out.write(cleanToken.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun readFromMediaStore(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            resolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(MEDIASTORE_TOKEN_NAME),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val uri = ContentUris.withAppendedId(collection, id)
                    resolver.openInputStream(uri)?.use { input ->
                        sanitizeToken(input.bufferedReader().readText())
                    }
                } else null
            }
        }.getOrNull()
    }

    private fun clearFromMediaStore(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(MEDIASTORE_TOKEN_NAME)
            )
        }
    }

    private fun getPersistentTokenFiles(context: Context? = null): List<File> {
        val files = mutableListOf<File>()
        runCatching {
            context?.getExternalFilesDir(null)?.let { files.add(File(it, TOKEN_BACKUP_FILENAME)) }
        }
        runCatching {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            files.add(File(downloadDir, TOKEN_BACKUP_FILENAME))
            files.add(File(downloadDir, MEDIASTORE_TOKEN_NAME))
        }
        runCatching {
            val extDir = Environment.getExternalStorageDirectory()
            files.add(File(extDir, TOKEN_BACKUP_FILENAME))
        }
        runCatching {
            files.add(File("/sdcard/Download/$TOKEN_BACKUP_FILENAME"))
            files.add(File("/sdcard/Download/$MEDIASTORE_TOKEN_NAME"))
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

        // Save to Settings.System / Global if permitted
        runCatching {
            Settings.System.putString(context.contentResolver, KEY_TOKEN, cleanToken)
        }
        runCatching {
            Settings.Global.putString(context.contentResolver, KEY_TOKEN, cleanToken)
        }

        // Save to MediaStore.Downloads (requires ZERO permissions on Android 10+ and survives uninstall)
        saveToMediaStore(context, cleanToken)

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

        // 1. Check Android Settings (Global / System / Secure) which requires zero permissions to read!
        val settingsToken = runCatching {
            sanitizeToken(Settings.Global.getString(context.contentResolver, KEY_TOKEN))
                ?: sanitizeToken(Settings.System.getString(context.contentResolver, KEY_TOKEN))
                ?: sanitizeToken(Settings.Secure.getString(context.contentResolver, KEY_TOKEN))
        }.getOrNull()

        if (settingsToken != null) {
            sp.edit { putString(KEY_TOKEN, settingsToken) }
            return settingsToken
        }

        // 2. Check MediaStore.Downloads
        val mediaStoreToken = readFromMediaStore(context)
        if (mediaStoreToken != null) {
            sp.edit { putString(KEY_TOKEN, mediaStoreToken) }
            return mediaStoreToken
        }

        // 3. Fallback: Check persistent file paths across uninstalls/reinstalls
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

        runCatching { Settings.System.putString(context.contentResolver, KEY_TOKEN, "") }
        runCatching { Settings.Global.putString(context.contentResolver, KEY_TOKEN, "") }
        clearFromMediaStore(context)

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
