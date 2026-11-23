package com.vscodroid.setup

import android.os.StatFs
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.BuildConfig
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * HTTP fallback downloader for sideloaded and debug installs without Play Asset Delivery.
 *
 * Coordinates release pinning, digest manifest and ECDSA P-256 signature verification,
 * resumable ZIP download, extraction, and abandoned staging directory cleanup on
 * [ToolchainManager.ioExecutor].
 */
internal class ToolchainHttpDownloader(private val manager: ToolchainManager) {

    /**
     * A request the release cannot answer, now or on a third attempt.
     */
    private class MissingFromRelease(message: String) : IOException(message)

    /**
     * A manifest signature that is missing, oversized, malformed, or fails ECDSA P-256 verification.
     */
    private class ManifestSignatureInvalid(message: String) : IOException(message)

    /**
     * A `Range` the origin will not serve: HTTP 416.
     */
    private class RangeRefused(message: String) : IOException(message)

    private companion object {
        private const val HTTP_TIMEOUT_MS = 30_000     // 30s connect + read timeout
        private const val MAX_RETRIES = 2
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private const val MAX_REDIRECTS = 5
    }

    /** Disconnects an active HTTP request off the calling thread so UI cancel stays non-blocking. */
    fun disconnectAsync(conn: HttpURLConnection) {
        Thread({ runCatching { conn.disconnect() } }, "toolchain-cancel").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The bytes free for a toolchain copy that needs [required], measured again after the strays
     * of a killed copy under `usr/` are removed when they are what stands between the two.
     */
    fun freeBytesFor(required: Long): Long {
        val free = StatFs(manager.context.filesDir.absolutePath).availableBytes
        if (free >= required || confinedToolchainPath(manager.context.filesDir, "usr") == null) return free
        if (sweepStrayTempsUnder(File(manager.context.filesDir, "usr")) == 0L) return free
        return StatFs(manager.context.filesDir.absolutePath).availableBytes
    }

    /**
     * The existing payload the record names for this build, without shared directories or links.
     *
     * No credit unless the record's `displayName` matches [displayName], the registry's name for
     * the build about to be downloaded.
     */
    private fun recordedInstallSpace(name: String, displayName: String?, recordedBytes: Long): InstalledTree {
        val state = manager.readStateFile()
        val filesDir = manager.context.filesDir
        for (i in 0 until state.length()) {
            val entry = state.optJSONObject(i) ?: continue
            if (entry.optString("name") != name) continue
            if (displayName == null || entry.optString("displayName") != displayName) return InstalledTree.NONE
            val paths = buildSet {
                add(entry.optString("installRoot", ""))
                ownedPaths(filesDir, entry, manager.tag).forEach { add(it.relativeTo(filesDir).path) }
                entry.optJSONArray("binaries")?.let { values ->
                    for (j in 0 until values.length()) add(values.optString(j, ""))
                }
                entry.optJSONArray("libs")?.let { values ->
                    for (j in 0 until values.length()) add("usr/lib/${values.optString(j, "")}")
                }
            }.filter(::isOwnedPathName).mapNotNull { confinedToolchainPath(filesDir, it) }
                .filterNot { Files.isSymbolicLink(it.toPath()) }
            var bytes = 0L
            var largest = 0L
            for (path in paths) {
                // A binary or owned subtree may already be covered by another recorded root.
                if (paths.any { it != path && path.toPath().startsWith(it.toPath()) }) continue
                val found = installedTreeSpace(path, filesDir, recordedBytes) { tree ->
                    sweepStrayTempsUnder(tree)
                    measureInstalledTree(tree)
                }
                bytes = minOf(recordedBytes, bytes + found.bytes)
                largest = maxOf(largest, found.largestFile)
            }
            return InstalledTree(bytes, minOf(bytes, largest))
        }
        return InstalledTree.NONE
    }

    /**
     * Downloads a toolchain ZIP from GitHub Releases, extracts it, and installs.
     * Runs entirely on [ToolchainManager.ioExecutor]. Fires `onStateChange` with `AssetPackStatus` constants.
     */
    fun downloadViaHttp(
        packName: String,
        url: String,
        unpackedBytes: Long,
        downloadBytes: Long,
    ) {
        // Admission and removal claims must agree before either queues file work.
        val download = synchronized(ToolchainManager.packOperationLock) {
            if (packName in ToolchainManager.installsInFlight) null
            else ToolchainManager.HttpDownload().takeIf { ToolchainManager.httpDownloads.putIfAbsent(packName, it) == null }
        }
        if (download == null) {
            Logger.i(manager.tag, "An install of $packName is already outstanding; not starting another")
            manager.reportState(packName, AssetPackStatus.UNKNOWN, 0)
            return
        }
        manager.reportState(packName, AssetPackStatus.PENDING, 0, download.transferId)

        manager.ioExecutor.execute {
            val tempDir = toolchainTempDir(manager.context.cacheDir, packName)
            val zipFile = File(tempDir, "$packName.zip")
            val extractDir = File(tempDir, packName)

            try {
                if (download.cancelled) {
                    Logger.i(manager.tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                val installed = recordedInstallSpace(
                    toolchainShortName(packName),
                    ToolchainRegistry.find(packName)?.displayName,
                    unpackedBytes,
                )
                val requiredBytes = toolchainInstallBytes(unpackedBytes, downloadBytes, installed)
                val availableBytes = freeBytesFor(requiredBytes)
                if (availableBytes < requiredBytes) {
                    Logger.e(
                        manager.tag,
                        "Not enough disk space: ${availableBytes / 1_000_000} MB available, " +
                            "${requiredBytes / 1_000_000} MB required",
                    )
                    manager.failState(packName, ToolchainFailure.STORAGE, download.transferId)
                    return@execute
                }

                tempDir.mkdirs()

                if (download.cancelled) {
                    Logger.i(manager.tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                // Resolve once before either request so the manifest and payload name the same release.
                val pinnedUrl = pinLatest(url, download)

                // Resolve expected digest before downloading the payload.
                val expectedDigest = publishedDigestFor(pinnedUrl, download)

                // Download
                retrying(packName, download) {
                    try {
                        downloadFile(packName, pinnedUrl, zipFile, downloadBytes, download)
                    } catch (e: RangeRefused) {
                        Logger.w(manager.tag, "$packName: the origin refuses to resume; starting again", e)
                        zipFile.delete()
                        throw e
                    }
                }

                if (download.cancelled) {
                    Logger.i(manager.tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                val actualDigest = sha256Of(zipFile)
                if (!actualDigest.equals(expectedDigest, ignoreCase = true)) {
                    Logger.e(
                        manager.tag,
                        "Digest mismatch for $packName: the release publishes $expectedDigest, " +
                            "the download hashes to $actualDigest. Not installing it.",
                    )
                    manager.failState(packName, ToolchainFailure.DIGEST, download.transferId)
                    return@execute
                }
                Logger.i(manager.tag, "$packName matches the digest the release publishes")

                if (download.cancelled) {
                    Logger.i(manager.tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                // Extract: report as TRANSFERRING (file copy phase)
                download.percent = 90
                manager.reportState(packName, AssetPackStatus.TRANSFERRING, 90, download.transferId)
                StorageManager.deleteRecursive(extractDir, manager.context.filesDir.parentFile)
                if (extractDir.exists() || Files.isSymbolicLink(extractDir.toPath())) {
                    manager.failState(packName, ToolchainFailure.STORAGE, download.transferId)
                    return@execute
                }
                extractDir.mkdirs()
                try {
                    extractZip(zipFile, extractDir, isCancelled = { download.cancelled })
                } catch (e: IOException) {
                    if (download.cancelled) {
                        Logger.i(manager.tag, "HTTP download cancelled for $packName")
                        return@execute
                    }
                    Logger.e(manager.tag, "Could not unpack $packName", e)
                    manager.failState(packName, ToolchainFailure.STORAGE, download.transferId)
                    return@execute
                }
                zipFile.delete()

                if (download.cancelled) {
                    Logger.i(manager.tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                if (installed.bytes > 0) {
                    val space = toolchainCopySpace(File(extractDir, "usr"), File(manager.context.filesDir, "usr"))
                    val needed = toolchainCopyBytes(unpackedBytes, space)
                    val free = freeBytesFor(needed)
                    if (free < needed) {
                        Logger.e(
                            manager.tag,
                            "Not enough disk space to replace $packName: " +
                                "${free / 1_000_000} MB available, ${needed / 1_000_000} MB required",
                        )
                        manager.failState(packName, ToolchainFailure.STORAGE, download.transferId)
                        return@execute
                    }
                }

                manager.installFromStaging(packName, extractDir, download.transferId)
            } catch (e: ManifestSignatureInvalid) {
                Logger.e(manager.tag, "Digest manifest signature check failed for $packName", e)
                manager.failState(packName, ToolchainFailure.DIGEST, download.transferId)
            } catch (e: MissingFromRelease) {
                Logger.e(manager.tag, "Not published in the release: $packName", e)
                manager.failState(packName, ToolchainFailure.NOT_PUBLISHED, download.transferId)
            } catch (e: IOException) {
                if (download.cancelled) {
                    Logger.i(manager.tag, "HTTP download cancelled for $packName")
                } else {
                    Logger.e(manager.tag, "HTTP download failed for $packName", e)
                    manager.failState(packName, ToolchainFailure.NETWORK, download.transferId)
                }
            } catch (e: SecurityException) {
                Logger.e(manager.tag, "Zip security violation for $packName", e)
                manager.failState(packName, ToolchainFailure.CORRUPT, download.transferId)
            } catch (e: Exception) {
                Logger.e(manager.tag, "Unexpected error downloading $packName", e)
                manager.failState(packName, ToolchainFailure.INTERNAL, download.transferId)
            } finally {
                val heldThePack = synchronized(download) { ToolchainManager.httpDownloads.remove(packName, download) }
                StorageManager.deleteRecursive(tempDir, manager.context.filesDir.parentFile)
                if (heldThePack && download.cancelled) {
                    manager.reportState(packName, AssetPackStatus.CANCELED, 0, download.transferId)
                }
            }
        }
    }

    /**
     * Runs [attempt] up to MAX_RETRIES + 1 times with exponential backoff.
     * Does not retry [MissingFromRelease].
     */
    @Throws(IOException::class)
    private fun <T> retrying(what: String, download: ToolchainManager.HttpDownload, attempt: () -> T): T {
        var lastException: IOException? = null
        for (n in 0..MAX_RETRIES) {
            if (download.cancelled) throw IOException("Download cancelled")
            try {
                if (n > 0) {
                    val backoffMs = (1L shl n) * 1000 // 2s, 4s
                    Logger.i(manager.tag, "Retry $n/$MAX_RETRIES for $what after ${backoffMs}ms")
                    download.waitBackoff(backoffMs)
                    if (download.cancelled) throw IOException("Download cancelled")
                }
                return attempt()
            } catch (e: ManifestSignatureInvalid) {
                throw e
            } catch (e: MissingFromRelease) {
                throw e
            } catch (e: IOException) {
                lastException = e
                if (download.cancelled) throw e
                Logger.w(manager.tag, "Attempt $n failed for $what: ${e.message}")
            }
        }
        throw lastException ?: IOException("$what failed after ${MAX_RETRIES + 1} attempts")
    }

    /** Binds [conn] to [download] so cancellation disconnects it immediately, or throws if already cancelled. */
    @Throws(IOException::class)
    private fun bindActiveConnection(download: ToolchainManager.HttpDownload?, conn: HttpURLConnection) {
        if (download == null) return
        val cancelled = synchronized(download) {
            if (download.cancelled) true
            else {
                download.activeConnection = conn
                false
            }
        }
        if (cancelled) {
            conn.disconnect()
            throw IOException("Download cancelled")
        }
    }

    private fun clearActiveConnection(download: ToolchainManager.HttpDownload?, conn: HttpURLConnection) {
        if (download == null) return
        synchronized(download) {
            if (download.activeConnection === conn) download.activeConnection = null
        }
    }

    @Throws(IOException::class)
    private fun <T> withRedirects(
        url: String,
        what: String,
        requestHeaders: Map<String, String> = emptyMap(),
        download: ToolchainManager.HttpDownload? = null,
        body: (HttpURLConnection) -> T,
    ): T {
        var currentUrl = url
        var redirects = 0

        while (redirects < MAX_REDIRECTS) {
            if (download?.cancelled == true) throw IOException("Download cancelled")
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            bindActiveConnection(download, conn)
            try {
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "VSCodroid")
                conn.setRequestProperty("Accept-Encoding", "identity")
                requestHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }

                val responseCode = conn.responseCode
                if (download?.cancelled == true) throw IOException("Download cancelled")

                if (responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect with no Location header from $currentUrl")
                    currentUrl = nextRedirectUrl(currentUrl, location)
                    redirects++
                    continue
                }

                if (responseCode == 404) {
                    throw MissingFromRelease("404 Not Found: $currentUrl ($what not uploaded to release?)")
                }

                if (responseCode == 416) {
                    throw RangeRefused("416 Range Not Satisfiable: $currentUrl ($what)")
                }

                if (responseCode != 200 && responseCode != 206) {
                    throw IOException("HTTP $responseCode from $currentUrl")
                }

                return body(conn)
            } finally {
                clearActiveConnection(download, conn)
                conn.disconnect()
            }
        }

        throw IOException("Too many redirects ($MAX_REDIRECTS) for $url")
    }

    @Throws(IOException::class)
    private fun fetchManifestBytes(
        url: String,
        download: ToolchainManager.HttpDownload,
        enforceExactLimit: Boolean,
    ): ByteArray = withRedirects(url, "digest manifest", download = download) { conn ->
        val limit = ToolchainManifestVerifier.MAX_MANIFEST_BYTES
        val declared = conn.contentLengthLong
        if (enforceExactLimit && declared > limit) {
            throw ManifestSignatureInvalid("Digest manifest exceeds $limit bytes ($declared declared)")
        }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        conn.inputStream.use { input ->
            while (true) {
                if (download.cancelled) throw IOException("Download cancelled")
                val remaining = (limit + 1) - out.size()
                if (remaining <= 0) break
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            if (download.cancelled) throw IOException("Download cancelled")
        }
        val bytes = out.toByteArray()
        if (bytes.size > limit) {
            if (enforceExactLimit) {
                throw ManifestSignatureInvalid("Digest manifest exceeds $limit bytes")
            }
            return@withRedirects bytes.copyOf(limit)
        }
        if (enforceExactLimit && !isCompleteTransfer(declared, bytes.size.toLong())) {
            throw IOException("Incomplete digest manifest: received ${bytes.size} bytes of $declared declared")
        }
        bytes
    }

    @Throws(IOException::class)
    private fun fetchManifestSignatureBytes(
        url: String,
        download: ToolchainManager.HttpDownload,
    ): ByteArray = withRedirects(url, "digest manifest signature", download = download) { conn ->
        val limit = ToolchainManifestVerifier.MAX_SIGNATURE_BYTES
        val declared = conn.contentLengthLong
        if (declared > limit) {
            throw ManifestSignatureInvalid("Digest manifest signature exceeds $limit bytes ($declared declared)")
        }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(limit + 1)
        conn.inputStream.use { input ->
            while (true) {
                if (download.cancelled) throw IOException("Download cancelled")
                val remaining = (limit + 1) - out.size()
                if (remaining <= 0) break
                val n = input.read(buf, 0, remaining)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            if (download.cancelled) throw IOException("Download cancelled")
        }
        val bytes = out.toByteArray()
        if (bytes.size > limit) {
            throw ManifestSignatureInvalid("Digest manifest signature exceeds $limit bytes")
        }
        if (!isCompleteTransfer(declared, bytes.size.toLong())) {
            throw IOException("Incomplete digest manifest signature: received ${bytes.size} bytes of $declared declared")
        }
        bytes
    }

    /**
     * [zipUrl] pointed at a concrete release, or [zipUrl] unchanged when none
     * can be resolved. Prefers this build's own release tag before resolving `latest`.
     */
    private fun pinLatest(zipUrl: String, download: ToolchainManager.HttpDownload): String {
        if (download.cancelled) return zipUrl
        ownReleaseAssetUrl(zipUrl, download)?.let {
            Logger.i(manager.tag, "Pinned this install to ${it.substringBeforeLast('/')}")
            return it
        }
        if (download.cancelled) return zipUrl
        val latestUrl = latestReleaseUrlFor(zipUrl) ?: return zipUrl
        val pinned = try {
            val conn = URL(latestUrl).openConnection() as HttpURLConnection
            bindActiveConnection(download, conn)
            try {
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", "VSCodroid")
                val code = conn.responseCode
                val location = if (code in 300..399) conn.getHeaderField("Location") else null
                location?.let { releaseTagFromLocation(it) }?.let { pinnedAssetUrl(zipUrl, it) }
            } finally {
                clearActiveConnection(download, conn)
                conn.disconnect()
            }
        } catch (e: IOException) {
            Logger.w(manager.tag, "Could not resolve the latest release: ${e.message}")
            null
        }
        if (pinned == null) {
            Logger.w(
                manager.tag,
                "Falling back to the unpinned release URL; the digest and the payload " +
                    "may come from different releases if one is published mid-download",
            )
            return zipUrl
        }
        Logger.i(manager.tag, "Pinned this install to ${pinned.substringBeforeLast('/')}")
        return pinned
    }

    /**
     * [zipUrl] served from this build's own release, or null.
     */
    private fun ownReleaseAssetUrl(zipUrl: String, download: ToolchainManager.HttpDownload): String? {
        val ownTag = appReleaseTag(BuildConfig.VERSION_NAME) ?: return null
        val url = pinnedAssetUrl(zipUrl, ownTag) ?: return null
        if (assetIsPublished(url, download)) return url
        Logger.i(
            manager.tag,
            "Release $ownTag does not publish ${zipUrl.substringAfterLast('/')}; " +
                "falling back to the latest release",
        )
        return null
    }

    /**
     * Whether the server will serve [url], asked with a HEAD and no body.
     */
    private fun assetIsPublished(url: String, download: ToolchainManager.HttpDownload): Boolean = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        bindActiveConnection(download, conn)
        try {
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", "VSCodroid")
            conn.responseCode in 200..399
        } finally {
            clearActiveConnection(download, conn)
            conn.disconnect()
        }
    } catch (e: IOException) {
        Logger.w(manager.tag, "Could not ask whether $url is published: ${e.message}")
        false
    }

    /**
     * The digest the release publishes for [zipUrl], or a refusal.
     *
     * Fetches the raw wire bytes of `toolchains.sha256` and, whenever a verification key is
     * configured or [ToolchainManifestVerifier.requireSignedManifest] is enabled, fetches
     * `toolchains.sha256.sig` and verifies the ECDSA P-256 signature over those exact bytes
     * before decoding or parsing the manifest.
     */
    @Throws(IOException::class)
    private fun publishedDigestFor(zipUrl: String, download: ToolchainManager.HttpDownload): String {
        val zipName = zipUrl.substringAfterLast('/')
        val publicKeySpki = ToolchainManifestVerifier.manifestPublicKeySpki
        val mustVerify = publicKeySpki != null || ToolchainManifestVerifier.requireSignedManifest
        val manifestBytes = retrying("$zipName digest manifest", download) {
            fetchManifestBytes(manifestUrlFor(zipUrl), download, enforceExactLimit = mustVerify)
        }
        if (mustVerify) {
            if (publicKeySpki == null) {
                throw ManifestSignatureInvalid(
                    "Signed digest manifest required for $zipName, but no valid verification key is configured"
                )
            }
            val sigBytes = try {
                retrying("$zipName digest manifest signature", download) {
                    fetchManifestSignatureBytes(manifestSignatureUrlFor(zipUrl), download)
                }
            } catch (e: MissingFromRelease) {
                throw ManifestSignatureInvalid(
                    "Digest manifest signature missing for $zipName: ${e.message}"
                )
            }
            if (!ToolchainManifestVerifier.verify(manifestBytes, sigBytes, publicKeySpki)) {
                throw ManifestSignatureInvalid(
                    "ECDSA P-256 signature verification failed for $zipName digest manifest"
                )
            }
            Logger.i(manager.tag, "Verified ECDSA P-256 signature for $zipName digest manifest")
        } else {
            Logger.w(
                manager.tag,
                "No manifest signing public key configured; relying on HTTPS-pinned SHA-256 verification",
            )
        }
        val manifest = manifestBytes.toString(Charsets.UTF_8)
        return digestFromManifest(manifest, zipName)
            ?: throw MissingFromRelease(
                "The release's digest manifest does not name $zipName; refusing to install a " +
                    "payload nothing vouches for"
            )
    }

    /**
     * Downloads the toolchain ZIP, reporting progress as DOWNLOADING, with Range resume support.
     */
    @Throws(IOException::class)
    private fun downloadFile(
        packName: String,
        url: String,
        destFile: File,
        expectedTransferBytes: Long,
        download: ToolchainManager.HttpDownload,
    ) {
        val alreadyHave = destFile.length()
        val headers =
            if (alreadyHave > 0) mapOf("Range" to "bytes=$alreadyHave-") else emptyMap()
        withRedirects(url, "toolchain ZIP", headers, download = download) { conn ->
            val resumed = conn.responseCode == 206
            val startAt = if (resumed) alreadyHave else 0L
            if (alreadyHave > 0 && !resumed) {
                Logger.i(manager.tag, "$packName: the server did not honour Range, starting again")
            }
            val declaredBytes = conn.contentLengthLong
            val totalBytes = if (declaredBytes > 0) startAt + declaredBytes else expectedTransferBytes

            fun percentOf(bytes: Long): Int =
                if (totalBytes > 0) ((bytes * 85) / totalBytes).toInt().coerceAtMost(85) else 0

            val startPercent = percentOf(startAt)
            manager.reportState(packName, AssetPackStatus.DOWNLOADING, startPercent, download.transferId)
            download.percent = startPercent

            var bytesRead = 0L
            var lastReported = startPercent
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(destFile, resumed).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var len: Int

                    while (input.read(buffer).also { len = it } != -1) {
                        if (download.cancelled) {
                            throw IOException("Download cancelled")
                        }
                        output.write(buffer, 0, len)
                        bytesRead += len
                        val percent = percentOf(startAt + bytesRead)
                        download.percent = percent
                        if (percent != lastReported) {
                            lastReported = percent
                            manager.reportState(packName, AssetPackStatus.DOWNLOADING, percent, download.transferId)
                        }
                    }
                }
            }

            if (!isCompleteTransfer(declaredBytes, bytesRead)) {
                throw IOException(
                    "Incomplete download for $packName: received $bytesRead bytes of $declaredBytes declared"
                )
            }

            Logger.i(manager.tag, "Downloaded $packName: ${destFile.length() / 1_000_000} MB")
        }
    }

    /**
     * Extracts a ZIP archive to the destination directory.
     * Enforces zip-slip protection, cancellation checks, and total byte/entry limits.
     */
    @Throws(IOException::class, SecurityException::class)
    private fun extractZip(
        zipFile: File,
        destDir: File,
        isCancelled: () -> Boolean = { false },
        maxBytes: Long = MAX_EXTRACT_BYTES,
        maxEntries: Int = MAX_EXTRACT_ENTRIES,
    ) {
        extractToolchainZip(
            zipFile = zipFile,
            destDir = destDir,
            appRoot = manager.context.filesDir.parentFile,
            isCancelled = isCancelled,
            maxBytes = maxBytes,
            maxEntries = maxEntries,
        )
        Logger.i(manager.tag, "Extracted ${zipFile.name} to ${destDir.absolutePath}")
    }

    /**
     * Deletes staging directories left behind by a download that never reached its `finally`.
     */
    fun sweepAbandonedDownloadsSync() {
        val root = File(manager.context.cacheDir, "toolchain-download")
        val entries = root.listFiles() ?: return
        val inFlight = ToolchainManager.packsDownloading().keys + ToolchainManager.installsInFlight
        val now = System.currentTimeMillis()
        var removed = 0
        for (entry in entries) {
            if (inFlight.any { entry.name.startsWith("$it-") }) continue
            if (!isAbandonedDownload(entry.lastModified(), now)) continue
            StorageManager.deleteRecursive(entry, manager.context.filesDir.parentFile)
            if (!entry.exists() && !Files.isSymbolicLink(entry.toPath())) removed++
        }
        if (removed > 0) {
            Logger.i(manager.tag, "Removed $removed abandoned toolchain download directories")
        }
    }
}
