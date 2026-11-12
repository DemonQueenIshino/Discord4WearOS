package com.demonqueenishino.Discord4WOS

import android.app.Application
import com.demonqueenishino.Discord4WOS.api.DiscordRepository

class DiscordWearApp : Application() {
    var repository: DiscordRepository? = null
        private set

    fun initRepository(token: String) {
        repository?.disconnect()
        val repo = DiscordRepository(token, context = this)
        repo.connect()
        repository = repo
    }

    fun clearRepository() {
        repository?.disconnect()
        repository = null
    }

    override fun onTerminate() {
        super.onTerminate()
        repository?.disconnect()
    }
}

val android.content.Context.discordApp: DiscordWearApp
    get() = applicationContext as DiscordWearApp
