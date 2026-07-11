package com.hermes.chat

import android.app.Application
import com.hermes.chat.data.local.AppDatabase

class HermesApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
