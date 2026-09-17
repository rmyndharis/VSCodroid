package com.vscodroid.webview

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

/**
 * The 32 bytes the workbench's secret storage is sealed with.
 *
 * The workbench keeps extension secrets (sign-in sessions, API keys) in its
 * `LocalStorageSecretStorageProvider` only when the `vscode-secret-key-path`
 * cookie is present, and it asks that path for this key before it seals or
 * opens them. See [VSCodroidWebViewClient.SECRET_KEY_PATH].
 *
 * The key must never change once it has been handed out. The workbench answers
 * a blob it cannot decrypt by deleting it, so a new key would sign the user out
 * of everything on the next start with nothing on screen to say why. It is
 * therefore made once, synced to storage before it is returned, read back on
 * every later start, and replaced only when the file is missing or cannot be a
 * key at all.
 *
 * Kept under `noBackupFilesDir`: the sealed secrets live in WebView storage,
 * which the backup rules leave out, so a backed-up key would be a key to
 * nothing on the device it was restored to.
 */
class SecretStorageKey(private val file: File) {

    private var cached: ByteArray? = null

    @Synchronized
    fun bytes(): ByteArray {
        val key = cached
            ?: file.takeIf { it.isFile }?.readBytes()?.takeIf { it.size == KEY_BYTES }
            ?: create()
        cached = key
        return key.copyOf()
    }

    private fun create(): ByteArray {
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val dir = file.parentFile ?: throw IOException("no directory for ${file.name}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
        val staged = File(dir, "${file.name}.tmp")
        FileOutputStream(staged).use { out ->
            out.write(key)
            out.fd.sync()
        }
        staged.setReadable(false, false)
        staged.setWritable(false, false)
        staged.setReadable(true, true)
        staged.setWritable(true, true)
        if (!staged.renameTo(file)) throw IOException("could not move ${staged.name} into place")
        return key
    }

    companion object {
        /** What the workbench accepts, and nothing else: `byteLength !== 256/8` throws. */
        const val KEY_BYTES = 32
        const val FILE_NAME = "secret-storage.key"

        @Volatile
        private var instance: SecretStorageKey? = null

        /**
         * The one store for this process. One, because two stores over the same
         * missing file could each make a key, and the page could be handed the one
         * whose rename lost.
         */
        fun forApp(context: Context): SecretStorageKey =
            instance ?: synchronized(this) {
                instance ?: SecretStorageKey(File(context.noBackupFilesDir, FILE_NAME))
                    .also { instance = it }
            }
    }
}
