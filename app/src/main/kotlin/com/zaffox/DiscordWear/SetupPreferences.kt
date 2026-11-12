package com.zaffox.discordwear

import android.content.Context
import androidx.core.content.edit

object SetupPreferences {
    private const val PREFS_NAME = "discord_wear_prefs"
    private const val KEY_TOKEN = "discord_token"
    private const val KEY_SPOILER_REVEAL_ON_TAP = "spoiler_reveal_on_tap"
    private const val KEY_SHOW_MENTION_BADGES = "show_mention_badges"
    private const val KEY_COMPACT_MODE = "compact_mode"

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_TOKEN, token) }
    }

    fun getToken(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_TOKEN) }
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
