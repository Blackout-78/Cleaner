package com.blackout.cleaner

import android.app.Application
import android.util.Log
import java.io.File

/** Saves any crash to a file so the next launch can show what went wrong. */
class CleanerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(filesDir, "crash.txt").writeText(Log.getStackTraceString(e)) }
            previous?.uncaughtException(t, e)
        }
    }
}
