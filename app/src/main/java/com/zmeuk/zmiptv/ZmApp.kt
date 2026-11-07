package com.zmeuk.zmiptv

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build

class ZmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val rt = Runtime.getRuntime()
        AppLog.i(
            "=== start ZmIPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}${if (BuildConfig.DEBUG) ", debug" else ""}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "heap limit ${rt.maxMemory() / MB} MB"
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            AppLog.crash(thread, e)
            previous?.uncaughtException(thread, e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // RUNNING_LOW / RUNNING_CRITICAL while in use, MODERATE / COMPLETE in the background; 20/40 just mean "hidden"
        val low = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (low) {
            AppLog.w("low memory (trim level $level): ${memory()}")
        }
    }

    companion object {
        private const val MB = 1024 * 1024

        fun memory(): String {
            val rt = Runtime.getRuntime()
            return "java heap ${(rt.totalMemory() - rt.freeMemory()) / MB} of ${rt.maxMemory() / MB} MB"
        }
    }
}
