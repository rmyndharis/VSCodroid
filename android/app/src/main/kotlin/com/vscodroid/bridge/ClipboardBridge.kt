package com.vscodroid.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.vscodroid.util.Logger

class ClipboardBridge(private val context: Context) {
    private val tag = "ClipboardBridge"

    private val clipboardManager: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copyToClipboard(text: String): Boolean {
        return try {
            val clip = ClipData.newPlainText("VSCodroid", text)
            clipboardManager.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Logger.e(tag, "Failed to copy to clipboard", e)
            false
        }
    }

    fun readFromClipboard(): String? {
        return try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context).toString()
            } else null
        } catch (e: Exception) {
            Logger.e(tag, "Failed to read clipboard", e)
            null
        }
    }

    fun hasClipboardText(): Boolean {
        return try {
            clipboardManager.hasPrimaryClip() &&
                    clipboardManager.primaryClipDescription?.hasMimeType("text/*") == true
        } catch (e: Exception) {
            false
        }
    }
}
