package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.StatFs
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A loopback HTTP responder built on [ServerSocket], answering fixed bytes for
 * fixed paths and recording what was asked for.
 *
 * Hand-rolled rather than taken from a library, and the reason is worth stating
 * so nobody replaces it with a dependency thinking they are tidying up. The JDK
 * has `com.sun.net.httpserver`, but this is an Android unit test: `android.jar`
 * replaces the JDK's bootclasspath, and that package is not in it. The
 * alternative is a new test dependency, which is a larger change than forty
 * lines of `ServerSocket` for a server that only ever answers two paths with
 * bytes chosen by the test.
 *
 * `Connection: close` on every response, so the client cannot hold a keep-alive
 * socket open and make the request counts ambiguous.
 */
private class LoopbackServer {
    private val socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = socket.localPort

    /** Path to body. An absent path answers 404, which is a release with no manifest. */
    @Volatile
    var routes: Map<String, ByteArray> = emptyMap()

    /** Every path asked for, in order, including ones that answered 404 or 503. */
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /**
     * Paths that answer 302 with a `Location`, so a test can be a release ALIAS
     * rather than an asset. Checked before [routes], and answered with no body,
     * which is what a `HEAD` gets anyway.
     *
     * Exists for the `latest` resolution: nothing else in this class needed a
     * redirect, and without one the only thing a test could check about pinning
     * was its string arithmetic.
     */
    @Volatile
    var redirects: Map<String, String> = emptyMap()

    /**
     * Paths that answer 503 for their first N requests and normally after that,
     * so a test can be a flaky connection rather than a broken one.
     */
    @Volatile
    var transientFailures: Map<String, Int> = emptyMap()

    private val failuresServed: MutableMap<String, Int> =
        Collections.synchronizedMap(mutableMapOf())

    private val thread = Thread {
        while (!socket.isClosed) {
            try {
                socket.accept().use { serve(it) }
            } catch (e: Exception) {
                if (socket.isClosed) return@Thread
            }
        }
    }.apply { isDaemon = true; name = "loopback-fixture" }

    fun start() = thread.start()

    fun stop() = socket.close()

    private fun serve(conn: Socket) {
        // A client that connects and then sends nothing would otherwise park
        // the accept loop, and the test would fail by timing out somewhere far
        // from the cause. Nothing does that today; this keeps it that way.
        conn.soTimeout = 5_000

        val reader = conn.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }

        val path = requestLine.split(" ").getOrNull(1) ?: "/"
        requested.add(path)

        val budget = transientFailures[path] ?: 0
        val alreadyFailed = failuresServed[path] ?: 0
        if (alreadyFailed < budget) {
            failuresServed[path] = alreadyFailed + 1
            conn.getOutputStream().apply {
                write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            conn.shutdownOutput()
            return
        }

        val out = conn.getOutputStream()

        val redirectTo = redirects[path]
        if (redirectTo != null) {
            out.write(
                ("HTTP/1.1 302 Found\r\nLocation: $redirectTo\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.flush()
            conn.shutdownOutput()
            return
        }

        val body = routes[path]
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            out.write(
                "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()
            )
            out.write(body)
        }
        out.flush()
        conn.shutdownOutput()
    }
}

/**
 * The HTTP delivery path end to end, against a loopback server.
 *
 * [ToolchainInstallTest] deliberately never opens a socket: it stubs `StatFs` to
 * report no free space so the pre-flight refusal is reached before any download.
 * That leaves everything past the pre-flight -- the manifest fetch, the digest
 * comparison, and the order the two happen in -- covered by nothing, and the
 * digest comparison is the entire point of this change. Deleting it would keep
 * every other test in the suite green.
 *
 * So this class serves the payload itself, from the [LoopbackServer] above on an
 * ephemeral port. Nothing leaves the machine and nothing is fetched from GitHub;
 * the ZIP is a few hundred bytes rather than the 179 MB the real one is.
 * [ToolchainRegistry.available] is stubbed to point at the loopback URL, which
 * is also what makes the manifest resolve there -- the production code derives
 * the manifest's URL from the ZIP's own.
 */
class ToolchainDigestInstallTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var packManager: AssetPackManager
    private lateinit var server: LoopbackServer

    private val events: MutableList<Triple<String, Int, Int>> =
        Collections.synchronizedList(mutableListOf())
    private val settled = CountDownLatch(1)

    /** Every failure reason reported, so a test can assert on WHY and not only that. */
    private val reasons: MutableList<ToolchainFailure> =
        Collections.synchronizedList(mutableListOf())

    /** Both derived from one directory, so a test can move the release. */
    private var releaseDir = "/download"
    private val zipPath get() = "$releaseDir/toolchain_test.zip"
    private val manifestPath get() = "$releaseDir/toolchains.sha256"

    /** A minimal but real pack: [ToolchainManager.installFromDirectory] needs this manifest. */
    private val zipBytes: ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("toolchain_test.json"))
            zip.write("""{"name":"test","installRoot":"usr/opt/test"}""".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private val zipDigest: String = MessageDigest.getInstance("SHA-256")
        .digest(zipBytes)
        .joinToString("") { "%02x".format(it) }

    private val zipUrl get() = "http://127.0.0.1:${server.port}$zipPath"

    /**
     * What this release publishes. The ZIP is always served; the manifest is
     * served only when a test says so, and its absence is a 404 -- which is
     * exactly what a release published without one answers.
     */
    private fun publishManifest(body: String?) {
        server.routes = buildMap {
            put(zipPath, zipBytes)
            if (body != null) put(manifestPath, body.toByteArray())
        }
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        packManager = mockk(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        // Room to spare, so the pre-flight passes and the download actually
        // happens -- the opposite of ToolchainInstallTest's arrangement, and the
        // reason both classes exist.
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns 8L * 1024 * 1024 * 1024

        packageManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.vscodroid"

        val source = mockk<InstallSourceInfo>()
        every { source.installingPackageName } returns "com.example.sideloader"
        every { packageManager.getInstallSourceInfo(any()) } returns source

        File(filesDir, "home/.vscodroid").mkdirs()

        server = LoopbackServer()
        server.start()
        mockkObject(ToolchainRegistry)
        publishFrom("/download")
    }

    /**
     * Points the registry, and therefore both URLs, at [dir] on the loopback
     * server. A test moves the release when the *shape of the URL* is what it
     * is about.
     */
    private fun publishFrom(dir: String) {
        releaseDir = dir
        publishManifest(null)

        val fixture = ToolchainRegistry.ToolchainInfo(
            packName = "toolchain_test",
            displayName = "Test",
            shortLabel = "Test",
            description = "loopback fixture",
            // The fixture serves the ZIP itself, so the two figures coincide
            // here in a way they never do for a real toolchain: nothing unpacks
            // it. The space gate reads estimatedSize, which is what this test
            // drives, and downloadSize only reaches the picker.
            estimatedSize = zipBytes.size.toLong(),
            downloadSize = zipBytes.size.toLong(),
            downloadUrl = zipUrl,
        )

        every { ToolchainRegistry.available } returns listOf(fixture)
        // `find` is stubbed as well as `available`, and it has to be: inside the
        // object's own body Kotlin reads the backing field rather than calling
        // the getter, so stubbing the property alone leaves `find` looking at
        // the real three toolchains. It then answered null for the fixture and
        // `install` reported FAILED from its unknown-toolchain branch -- a
        // refusal, arriving before any socket was opened, which every negative
        // assertion in this class would have accepted as proof of the check
        // working.
        every { ToolchainRegistry.find(any()) } answers {
            if (firstArg<String>() in setOf("toolchain_test", "test")) fixture else null
        }
    }

    @AfterEach
    fun tearDown() {
        server.stop()
        unmockkAll()
    }

    private fun timesRequested(path: String) = synchronized(server.requested) {
        server.requested.count { it == path }
    }

    /**
     * The per-request cancellation tokens `downloadViaHttp` keeps while a
     * download is outstanding. Reached by reflection because the alternative is
     * exposing a download's bookkeeping on the public surface so a test can
     * watch it, which makes the class worse to make the test simpler.
     */
    @Suppress("UNCHECKED_CAST")
    private fun outstandingOf(m: ToolchainManager): Map<String, Any> =
        ToolchainManager::class.java.getDeclaredField("httpDownloads")
            .apply { isAccessible = true }
            .get(m) as Map<String, Any>

    private fun manager() = ToolchainManager(context).apply {
        onStateChange = { pack, status, pct, why ->
            if (why != null) reasons.add(why)
            events.add(Triple(pack, status, pct))
            if (status == AssetPackStatus.COMPLETED || status == AssetPackStatus.FAILED) {
                settled.countDown()
            }
        }
    }

    private fun installAndWait() {
        manager().install("toolchain_test")
        assertTrue(settled.await(30, TimeUnit.SECONDS), "the install never reported an outcome: $events")
    }

    private fun statuses() = synchronized(events) { events.map { it.second } }

    private fun recorded(): String {
        val state = File(filesDir, "home/.vscodroid/toolchains.json")
        return if (state.exists()) state.readText() else ""
    }

    // ── the payload is what the release published ────────────────────────

    @Test
    fun `a zip matching the published digest installs`() {
        // The positive control, and it carries most of the weight: without it
        // every refusal below would pass against a build that refused
        // everything, which would be a worse bug than the one being fixed.
        publishManifest("$zipDigest  toolchain_test.zip\n")

        installAndWait()

        assertEquals(AssetPackStatus.COMPLETED, statuses().last(), "expected an install, got $events")
        assertTrue(recorded().contains("\"test\""), "the install was reported but not recorded: ${recorded()}")
    }

    // ── the payload is not what the release published ────────────────────

    @Test
    fun `a zip whose digest does not match is refused and nothing is installed`() {
        // The defect, as a test. Before this change the bytes were extracted,
        // chmodded and symlinked into usr/ with nothing having looked at them.
        //
        // Deleting the digest comparison in downloadViaHttp turns this red and
        // leaves every test in ToolchainInstallTest green, because that class
        // never reaches past the disk-space pre-flight.
        publishManifest("${"0".repeat(64)}  toolchain_test.zip\n")

        installAndWait()

        assertEquals(AssetPackStatus.FAILED, statuses().last(), "a mismatched payload was not refused: $events")
        assertFalse(recorded().contains("\"test\""), "a mismatched payload was recorded as installed")
        assertFalse(
            File(filesDir, "usr/opt/test").exists(),
            "a mismatched payload was extracted into the install tree",
        )
    }

    @Test
    fun `a release with no manifest refuses rather than trusting the zip`() {
        // The fallback question, answered. Treating an absent manifest as
        // permission to install is the behaviour being removed, so it must not
        // be what happens when the manifest 404s.
        publishManifest(null)

        installAndWait()

        assertEquals(AssetPackStatus.FAILED, statuses().last(), "an unverifiable release installed anyway: $events")
        assertFalse(recorded().contains("\"test\""))
    }

    @Test
    fun `a manifest that does not name this zip refuses it`() {
        // A release that packaged this ZIP and published digests for the others.
        // The manifest fetch succeeds, so this is a different path from the 404
        // above and fails for a different reason.
        publishManifest("${"a".repeat(64)}  toolchain_other.zip\n")

        installAndWait()

        assertEquals(AssetPackStatus.FAILED, statuses().last())
        assertFalse(recorded().contains("\"test\""))
    }

    // ── the order the two fetches happen in ──────────────────────────────

    @Test
    fun `the zip is not downloaded when the release cannot vouch for it`() {
        // Ordering, which no assertion about the outcome can see: refusing after
        // downloading is just as safe and costs the user 179 MB on a phone
        // connection before saying no. The request counters are the only way to
        // tell the two apart.
        publishManifest(null)

        installAndWait()

        assertEquals(1, timesRequested(manifestPath), "the manifest was not fetched")
        assertEquals(
            0, timesRequested(zipPath),
            "the payload was downloaded before the release was asked to vouch for it",
        )
    }

    // ── the manifest is as fault-tolerant as the payload ─────────────────

    /**
     * The manifest fetch runs *before* the ZIP, and the ZIP has always had three
     * attempts with backoff. Putting a zero-tolerance request in front of a
     * fault-tolerant one would mean a single stall on mobile data ends an
     * install that used to survive it -- a hardening change lowering the odds of
     * getting a payload at all.
     *
     * Taking `retrying(...)` back out of `publishedDigestFor` turns this red:
     * the 503 becomes the outcome instead of a hiccup.
     */
    @Test
    fun `a manifest request that fails once is retried rather than ending the install`() {
        publishManifest("$zipDigest  toolchain_test.zip\n")
        server.transientFailures = mapOf(manifestPath to 1)

        installAndWait()

        assertEquals(
            AssetPackStatus.COMPLETED, statuses().last(),
            "one failed manifest request ended the install: $events",
        )
        assertEquals(2, timesRequested(manifestPath), "the manifest request was not retried")
    }

    /**
     * Retryability is decided by the exception's type, not by whether `"404"`
     * happens to appear in its text.
     *
     * Three of the retryable messages carry a URL -- a redirect with no
     * `Location`, a non-200 status, and running out of hops -- and after
     * GitHub's redirect that URL is a signed `objects.githubusercontent.com`
     * link full of hex. A 64-character hex string contains `404` about 1.5% of
     * the time (measured over 400k samples), and such a URL has several hex
     * components, so a real slice of transient failures used to be read as
     * permanent and given up on after one attempt instead of three.
     *
     * The release directory here contains `404` for exactly that reason: the
     * server answers 503 once, which is retryable, and its message carries the
     * URL. Restoring `e.message?.contains("404")` in place of the
     * `MissingFromRelease` catch turns this red -- the install gives up on the
     * first attempt and reports FAILED.
     */
    @Test
    fun `a retryable failure whose URL contains 404 is still retried`() {
        publishFrom("/releases/a404b/download")
        publishManifest("$zipDigest  toolchain_test.zip\n")
        server.transientFailures = mapOf(manifestPath to 1)

        installAndWait()

        assertEquals(
            AssetPackStatus.COMPLETED, statuses().last(),
            "a transient failure was treated as permanent because its URL contained 404: $events",
        )
        assertEquals(2, timesRequested(manifestPath), "the request was not retried")
    }

    /**
     * A 404 must still fail on the first attempt. Retrying it would spend two
     * backoffs re-asking for a file the release does not carry, and the answer
     * would not change.
     */
    @Test
    fun `a missing manifest is not retried`() {
        publishManifest(null)

        installAndWait()

        assertEquals(AssetPackStatus.FAILED, statuses().last())
        assertEquals(1, timesRequested(manifestPath), "a 404 manifest was retried")
    }

    // ── cancellation is honoured before the first request ────────────────

    /**
     * Every other read of the cancellation flag sits past the manifest fetch, so
     * a pack cancelled while queued behind another one still spent a request --
     * and on a stalled connection, up to three read timeouts of it, with the
     * first-run queue waiting behind it.
     *
     * Made deterministic by blocking the task inside the disk-space pre-flight,
     * which is the last thing that happens before the check under test, rather
     * than racing `cancel()` against the executor.
     */
    @Test
    fun `a cancelled install does not request anything`() {
        publishManifest("$zipDigest  toolchain_test.zip\n")

        val reachedPreflight = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { anyConstructed<StatFs>().availableBytes } answers {
            reachedPreflight.countDown()
            release.await(10, TimeUnit.SECONDS)
            8L * 1024 * 1024 * 1024
        }

        val manager = manager()
        manager.install("toolchain_test")
        assertTrue(reachedPreflight.await(10, TimeUnit.SECONDS), "the task never reached the pre-flight")

        manager.cancel("toolchain_test")
        release.countDown()

        // A cancellation reports no status, so the task's own bookkeeping is
        // what says it finished: downloadViaHttp drops its token in a finally.
        val outstanding = outstandingOf(manager)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (outstanding.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(outstanding.isEmpty(), "the cancelled task never finished")

        assertEquals(0, timesRequested(manifestPath), "a cancelled install still fetched the manifest")
        assertEquals(0, timesRequested(zipPath), "a cancelled install still fetched the payload")
    }

    @Test
    fun `a verifiable release fetches the manifest and then the payload`() {
        // The other half of the ordering test: it must not conclude that
        // "never downloads the ZIP" is correct behaviour in general.
        publishManifest("$zipDigest  toolchain_test.zip\n")

        installAndWait()

        assertEquals(1, timesRequested(manifestPath))
        assertEquals(1, timesRequested(zipPath), "the payload was not downloaded on a release that vouches for it")
    }

    /**
     * The pin, proved by behaviour rather than by string arithmetic.
     *
     * `LatestReleasePinningTest` checks the URL functions against inputs it
     * chooses, which cannot tell a pin that engages from one that is never
     * called. Here the release is served ONLY under its concrete tag: the
     * `latest/download/` paths answer 404. So the install can succeed only if
     * `pinLatest` resolved the alias and built both URLs from the answer, and
     * deleting the call turns this red rather than leaving it green.
     */
    @Test
    fun `an install resolves latest once and fetches both files from that release`() {
        publishFrom("/o/r/releases/latest/download")
        val tagged = "/o/r/releases/download/v9.9.9"

        server.redirects = mapOf(
            "/o/r/releases/latest" to "http://127.0.0.1:${server.port}/o/r/releases/tag/v9.9.9"
        )
        // Deliberately NOT serving anything under latest/download/. If the pin
        // does not engage, both requests 404 and the install reports FAILED.
        server.routes = mapOf(
            "$tagged/toolchain_test.zip" to zipBytes,
            "$tagged/toolchains.sha256" to "$zipDigest  toolchain_test.zip\n".toByteArray(),
        )

        installAndWait()

        assertTrue(
            statuses().contains(AssetPackStatus.COMPLETED),
            "the install did not complete, so the alias was never resolved: ${statuses()}",
        )
        assertEquals(1, timesRequested("/o/r/releases/latest"), "the alias was not resolved exactly once")
        assertEquals(1, timesRequested("$tagged/toolchains.sha256"), "the manifest did not come from the pinned release")
        assertEquals(1, timesRequested("$tagged/toolchain_test.zip"), "the payload did not come from the pinned release")
        assertEquals(
            0,
            timesRequested("/o/r/releases/latest/download/toolchain_test.zip"),
            "the unpinned URL was used even though the alias resolved",
        )
    }

    /**
     * And the floor underneath it.
     *
     * The alias answers 404 here, so nothing can be pinned. The install must
     * still work, through the unpinned URL, because that is what shipped before
     * pinning existed and a resolution failure must never cost more than the
     * behaviour it replaced.
     */
    @Test
    fun `an install still works when the alias cannot be resolved`() {
        publishFrom("/o/r/releases/latest/download")

        // No redirects map at all: /o/r/releases/latest answers 404.
        publishManifest("$zipDigest  toolchain_test.zip\n")

        installAndWait()

        assertTrue(
            statuses().contains(AssetPackStatus.COMPLETED),
            "a failed resolution cost the install: ${statuses()}",
        )
        assertEquals(1, timesRequested(manifestPath), "the manifest was not fetched from the unpinned URL")
        assertEquals(1, timesRequested(zipPath), "the payload was not fetched from the unpinned URL")
    }

    @Test
    fun `a release that does not publish the manifest says so, and does not blame the network`() {
        // The manifest 404s, which arrives as MissingFromRelease. It extends
        // IOException and used to be caught with it, so the user was told to
        // check their connection for a file the release does not contain.
        publishManifest(null)

        installAndWait()

        assertTrue(statuses().contains(AssetPackStatus.FAILED), "the install should have been refused")
        assertEquals(
            listOf(ToolchainFailure.NOT_PUBLISHED),
            synchronized(reasons) { reasons.toList() },
            "a file the release never published is not a network fault",
        )
    }

    @Test
    fun `a payload that does not match the published digest says that`() {
        // A manifest that names the ZIP with someone else's digest. The bytes
        // arrive complete and wrong, which is the one case Content-Length cannot
        // see, and the reason has to distinguish it from a dropped connection.
        publishManifest("${"0".repeat(64)}  toolchain_test.zip\n")

        installAndWait()

        assertEquals(
            listOf(ToolchainFailure.DIGEST),
            synchronized(reasons) { reasons.toList() },
            "a complete body with the wrong digest is its own answer",
        )
    }
}
