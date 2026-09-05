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
     * is the accessor, and the coercion only for an item that carries NO URI.
     *
     * The mime test alone does not do it, and believing it did was the second
     * version of this bug. `ClipDescription.hasMimeType` matches the `text/` wildcard against
     * every type the clip declares, and a file manager copying a `.kt`, a `.md` or
     * a `.txt` declares `text/plain`; `ClipData.newUri` also appends
     * `text/uri-list` for a `content://` URI when the provider resolves no type.
     * Either way the description says text, `getText` is null because an item built
     * from a URI has none, and the coercion opened the document after all. The URI
     * is the thing that makes `coerceToText` a provider read, so the URI is what
     * has to be tested.
     *
     * What the coercion still buys is a styled or HTML clip, whose plain text it
     * renders and whose text field can be absent; such a clip carries no URI, so it
     * is unaffected. [hasClipboardText] asks the same question of the same clip,
     * because the two disagreeing is what let the guard say "no text here" while
     * the read returned a file.
     */
    fun readFromClipboard(): String? {
        return try {
            val clip = clipboardManager.primaryClip
            if (clip == null || clip.itemCount == 0) return null
            val item = clip.getItemAt(0)
            item.text?.toString() ?: if (offersTextWithoutReading(clip, item)) {
                item.coerceToText(context).toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e(tag, "Failed to read clipboard", e)
            null
        }
    }

    /**
     * Whether there is text to read, answered by the same rule [readFromClipboard]
     * uses.
     *
     * Off the clip rather than off `primaryClipDescription`, so the two cannot be
     * asked about different clips, and including the item test so they cannot
     * disagree about the same one: a description-only answer says "text here" for
     * the file-manager clip the read now declines, which is the shape of the
     * original defect with the two halves swapped.
     */
    fun hasClipboardText(): Boolean {
        return try {
            val clip = clipboardManager.primaryClip ?: return false
            if (clip.itemCount == 0) return false
            val item = clip.getItemAt(0)
            item.text != null || offersTextWithoutReading(clip, item)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Whether [item] can be coerced to text without opening a provider.
     *
     * Both halves are needed. The description must say text, or a clip of some
     * other kind would be rendered; and the item must carry no URI, because that
     * is the only thing `coerceToText` opens. An item with neither text nor URI
     * nor Intent coerces to the empty string, which is harmless.
     */
    private fun offersTextWithoutReading(clip: ClipData, item: ClipData.Item): Boolean =
        clip.description?.hasMimeType("text/*") == true && item.uri == null
}
