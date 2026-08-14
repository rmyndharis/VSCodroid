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

    /** Every path asked for, in order, including ones that answered 404. */
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())

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
        val reader = conn.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }

        val path = requestLine.split(" ").getOrNull(1) ?: "/"
        requested.add(path)

        val body = routes[path]
        val out = conn.getOutputStream()
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

    private val zipPath = "/download/toolchain_test.zip"
    private val manifestPath = "/download/toolchains.sha256"

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
        publishManifest(null)

        val fixture = ToolchainRegistry.ToolchainInfo(
            packName = "toolchain_test",
            displayName = "Test",
            shortLabel = "Test",
            description = "loopback fixture",
            estimatedSize = zipBytes.size.toLong(),
            downloadUrl = zipUrl,
        )

        mockkObject(ToolchainRegistry)
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

    private fun manager() = ToolchainManager(context).apply {
        onStateChange = { pack, status, pct ->
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

    @Test
    fun `a verifiable release fetches the manifest and then the payload`() {
        // The other half of the ordering test: it must not conclude that
        // "never downloads the ZIP" is correct behaviour in general.
        publishManifest("$zipDigest  toolchain_test.zip\n")

        installAndWait()

        assertEquals(1, timesRequested(manifestPath))
        assertEquals(1, timesRequested(zipPath), "the payload was not downloaded on a release that vouches for it")
    }
}
