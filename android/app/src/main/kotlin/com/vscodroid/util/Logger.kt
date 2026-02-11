package com.vscodroid.util

import android.content.Context
import android.util.Log

object Logger {
    private const val TAG_PREFIX = "VSCodroid"
    var debugEnabled = false
        private set

    fun init(context: Context) {
        debugEnabled = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun d(tag: String, message: String) {
        if (debugEnabled) Log.d("$TAG_PREFIX.$tag", message)
    }

    fun i(tag: String, message: String) {
        Log.i("$TAG_PREFIX.$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w("$TAG_PREFIX.$tag", message, throwable)
        else Log.w("$TAG_PREFIX.$tag", message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e("$TAG_PREFIX.$tag", message, throwable)
        else Log.e("$TAG_PREFIX.$tag", message)
    }
}
