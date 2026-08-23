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

    /**
     * The text on the clipboard, or null when there is none.
     *
     * `coerceToText` is not a text accessor and this used to call it
     * unconditionally. Its documented behaviour for an item that holds no text
     * but a URI is to OPEN that URI through this app's own `ContentResolver` and
     * return the stream's contents, falling back to the display name and then to
     * the URI itself. So copying a file in a file manager, which puts a
     * `content://` URI on the clipboard, turned this into a provider read
     * performed with VSCodroid's identity, on the bridge thread, with no size or
     * time bound, handing a document the caller never asked for to page
     * JavaScript.
     *
     * The item's own [android.content.ClipData.Item.getText] first, because that
     * is the accessor, and the coercion only for a clip whose description already
     * says it is text. That is the same predicate [hasClipboardText] applies, and
     * the two disagreeing is what let the guard say "no text here" while the read
     * returned a file. It is asked of the clip in hand rather than through that
     * method, so the answer cannot be about a different clip from the one being
     * read. What the coercion still buys on this path is a styled or HTML clip,
     * whose plain text it renders and whose text field can be absent.
     */
    fun readFromClipboard(): String? {
        return try {
            val clip = clipboardManager.primaryClip
            if (clip == null || clip.itemCount == 0) return null
            val item = clip.getItemAt(0)
            val isText = clip.description?.hasMimeType("text/*") == true
            item.text?.toString()
                ?: if (isText) item.coerceToText(context).toString() else null
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
