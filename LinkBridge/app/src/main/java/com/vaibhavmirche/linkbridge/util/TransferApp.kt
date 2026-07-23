package com.vaibhavmirche.linkbridge.util

import android.app.Application
import timber.log.Timber

class TransferApp : Application() {
    companion object {
        lateinit var memoryTree: InMemoryLogTree
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // 1) Plant the debug tree
        Timber.plant(Timber.DebugTree())
        // 2) Plant custom in‑memory tree
        memoryTree = InMemoryLogTree()
        Timber.plant(memoryTree)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "Uncaught exception in thread ${thread.name}")
        }
    }
}