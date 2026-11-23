package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.StatFs
import android.system.Os
import android.system.StructStat
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.android.play.core.assetpacks.AssetPackLocation
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ToolchainLifecycleHardeningTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var packManager: AssetPackManager
    private lateinit var assets: AssetManager
    private val managers = mutableListOf<ToolchainManager>()
    private val reports: MutableList<Pair<Int, ToolchainFailure?>> =
        Collections.synchronizedList(mutableListOf())
    private val removalCompletions = Collections.synchronizedList(mutableListOf<() -> Unit>())
    private val stateFile get() = File(filesDir, "home/.vscodroid/toolchains.json")
    private val envFile get() = File(filesDir, "home/.vscodroid/toolchain-env.sh")

    private var savedPublicKeySpki: ByteArray? = null
    private var savedRequireSigned: Boolean = false

    @BeforeEach
    fun setUp() {
        savedPublicKeySpki = ToolchainManifestVerifier.manifestPublicKeySpki
        savedRequireSigned = ToolchainManifestVerifier.requireSignedManifest
        ToolchainManifestVerifier.manifestPublicKeySpki = null
        ToolchainManifestVerifier.requireSignedManifest = false

        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns 8L * 1024 * 1024 * 1024

        packManager = mockk(relaxed = true)
        every { packManager.getPackLocation(any()) } returns null
        every { packManager.removePack(any()) } answers { successfulRemovalTask() }
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        mockkStatic(Os::class)
        every { Os.lstat(any()) } answers {
            val path = File(firstArg<String>()).toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw FileNotFoundException(path.toString())
            }
            mockk<StructStat>(relaxed = true)
        }
        every { Os.symlink(any(), any()) } answers {
            Files.createSymbolicLink(File(secondArg<String>()).toPath(), File(firstArg<String>()).toPath())
            Unit
        }
        every { Os.readlink(any()) } answers {
            Files.readSymbolicLink(File(firstArg<String>()).toPath()).toString()
        }
        every { Os.rename(any(), any()) } answers {
            Files.move(
                File(firstArg<String>()).toPath(),
                File(secondArg<String>()).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            Unit
        }

        packageManager = mockk(relaxed = true)
        installedBy("com.android.vending")

        assets = mockk(relaxed = true)
        every { assets.list("usr/lib") } returns arrayOf("libbase.so")

        val appInfo = mockk<ApplicationInfo>(relaxed = true)
        appInfo.nativeLibraryDir = File(filesDir, "native").absolutePath

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns packageManager
        every { context.assets } returns assets
        every { context.applicationInfo } returns appInfo

        stateFile.parentFile!!.mkdirs()
        reports.clear()
        clearStaticState()
        bridgeDeleteRecursiveIfNoSecureStream()
    }

    @AfterEach
    fun tearDown() {
        ToolchainManifestVerifier.manifestPublicKeySpki = savedPublicKeySpki
        ToolchainManifestVerifier.requireSignedManifest = savedRequireSigned
        for (m in managers) {
            val executor = ToolchainManager::class.java.getDeclaredField("ioExecutor")
                .apply { isAccessible = true }.get(m) as ExecutorService
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
        managers.clear()
        removalCompletions.toList().forEach { it() }
        removalCompletions.clear()
        clearStaticState()
        unmockkAll()
    }

    @Suppress("UNCHECKED_CAST")
    private fun clearStaticState() {
        for (name in listOf(
            "installsInFlight",
            "packRemovalsInFlight",
            "pendingPackReleases",
            "packReleaseGenerations",
            "httpDownloads",
        )) {
            val value = ToolchainManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null)
            when (value) {
                is MutableCollection<*> -> value.clear()
                is MutableMap<*, *> -> value.clear()
            }
        }
    }

    /** Bridges SecureDirectoryStream absence on BSD/macOS JDKs without altering Linux CI behavior. */
    private fun bridgeDeleteRecursiveIfNoSecureStream() {
        val supportsSecure = Files.newDirectoryStream(filesDir.toPath()).use {
            it is SecureDirectoryStream<*>
        }
        if (supportsSecure) return
        mockkObject(StorageManager)
        every { StorageManager.deleteRecursive(any(), any(), any()) } answers {
            val dir = firstArg<File>()
            val appRoot = secondArg<File?>()
            val keepSockets = thirdArg<Boolean>()
            if (appRoot == null) return@answers 0L
            val canonicalRoot = appRoot.canonicalFile.toPath()
            val rawRoot = appRoot.toPath().toAbsolutePath().normalize()
            val target = dir.toPath().toAbsolutePath().normalize()
            if (target == canonicalRoot || target == rawRoot) return@answers 0L
            if (!target.startsWith(canonicalRoot) && !target.startsWith(rawRoot)) return@answers 0L
            var freed = 0L
            fun remove(path: Path) {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
                if (Files.isSymbolicLink(path)) {
                    Files.deleteIfExists(path)
                    return
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.list(path).use { stream -> stream.forEach(::remove) }
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    freed += Files.size(path)
                } else if (keepSockets) {
                    return
                }
                Files.deleteIfExists(path)
            }
            runCatching { remove(target) }
            freed
        }
    }

    private fun installedBy(installer: String?) {
        val source = mockk<InstallSourceInfo>()
        every { source.installingPackageName } returns installer
        every { packageManager.getInstallSourceInfo(any()) } returns source
    }

    private fun manager() = ToolchainManager(context).also { managers.add(it) }.apply {
        onStateChange = { _, status, _, why, _ -> reports.add(status to why) }
    }

    private fun file(rel: String, text: String = "payload") = File(filesDir, rel).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun elf(rel: String): File = File(filesDir, rel).apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(32))
        setExecutable(false, false)
    }

    private fun link(rel: String, targetRel: String): File = File(filesDir, rel).apply {
        parentFile?.mkdirs()
        Files.createSymbolicLink(toPath(), File(filesDir, targetRel).toPath())
    }

    private fun existsNoFollow(rel: String) =
        Files.exists(File(filesDir, rel).toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun installMarkerFile(pack: String) =
        File(filesDir, "home/.vscodroid/toolchain-installing/$pack.json")

    private fun releaseMarkerFile(pack: String) =
        File(filesDir, "home/.vscodroid/toolchain-uninstalled/$pack")

    private fun ioExecutorOf(m: ToolchainManager): ExecutorService =
        ToolchainManager::class.java.getDeclaredField("ioExecutor")
            .apply { isAccessible = true }
            .get(m) as ExecutorService

    private fun successfulRemovalTask(): Task<Void> {
        val task = mockk<Task<Void>>(relaxed = true)
        every { task.isSuccessful } returns true
        every { task.addOnSuccessListener(any()) } answers {
            firstArg<OnSuccessListener<in Void>>().onSuccess(null)
            task
        }
        every { task.addOnFailureListener(any()) } returns task
        every { task.addOnCompleteListener(any<Executor>(), any<OnCompleteListener<Void>>()) } answers {
            firstArg<Executor>().execute { secondArg<OnCompleteListener<Void>>().onComplete(task) }
            task
        }
        return task
    }

    private fun uninstallSync(name: String, m: ToolchainManager = manager()) {
        ToolchainManager::class.java
            .getDeclaredMethod("uninstallSync", String::class.java)
            .apply { isAccessible = true }
            .invoke(m, name)
    }

    private fun installFromDirectory(m: ToolchainManager, pack: String, dir: File): Boolean =
        ToolchainManager::class.java
            .getDeclaredMethod("installFromDirectory", String::class.java, File::class.java)
            .apply { isAccessible = true }
            .invoke(m, pack, dir) as Boolean

    private fun reclaimInterrupted(m: ToolchainManager = manager()) {
        ToolchainManager::class.java
            .getDeclaredMethod("reclaimInterruptedInstallsSync")
            .apply { isAccessible = true }
            .invoke(m)
    }

    private fun repairSync(m: ToolchainManager = manager()) {
        ToolchainManager::class.java
            .getDeclaredMethod("repairInstalledToolchainsSync")
            .apply { isAccessible = true }
            .invoke(m)
    }

    @Suppress("UNCHECKED_CAST")
    private fun httpDownloadsMap(): MutableMap<String, Any> =
        ToolchainManager::class.java.getDeclaredField("httpDownloads")
            .apply { isAccessible = true }
            .get(null) as MutableMap<String, Any>

    private fun newDownloadToken(): Any =
        Class.forName("com.vscodroid.setup.ToolchainManager\$HttpDownload")
            .declaredConstructors
            .first()
            .apply { isAccessible = true }
            .newInstance()

    private fun playHolds(pack: String, dir: File) {
        val location = mockk<AssetPackLocation>(relaxed = true)
        every { location.assetsPath() } returns dir.absolutePath
        every { packManager.getPackLocation(pack) } returns location
    }

    private fun deliverPack(pack: String, name: String, root: String = "usr/opt/$name"): File =
        File(filesDir, "delivered/$pack").apply {
            mkdirs()
            File(this, "$pack.json").writeText("""{"name":"$name","installRoot":"$root"}""")
            File(this, "$root/bin").mkdirs()
            File(this, "$root/bin/$name").writeText("delivered-$name")
        }

    private fun controllableRemovalTask(): Triple<Task<Void>, () -> Unit, (Exception) -> Unit> {
        val task = mockk<Task<Void>>(relaxed = true)
        var successful = false
        var onSuccess: OnSuccessListener<in Void>? = null
        var onFailure: OnFailureListener? = null
        var onComplete: Pair<Executor, OnCompleteListener<Void>>? = null
        every { task.isSuccessful } answers { successful }
        every { task.addOnSuccessListener(any()) } answers {
            onSuccess = firstArg()
            task
        }
        every { task.addOnFailureListener(any()) } answers {
            onFailure = firstArg()
            task
        }
        every { task.addOnCompleteListener(any<Executor>(), any<OnCompleteListener<Void>>()) } answers {
            onComplete = firstArg<Executor>() to secondArg()
            removalCompletions.add {
                onComplete?.let { (exec, listener) -> exec.execute { listener.onComplete(task) } }
            }
            task
        }
        val succeed: () -> Unit = {
            successful = true
            onSuccess?.onSuccess(null)
            onComplete?.let { (exec, listener) -> exec.execute { listener.onComplete(task) } }
        }
        val fail: (Exception) -> Unit = { e ->
            successful = false
            onFailure?.onFailure(e)
            onComplete?.let { (exec, listener) -> exec.execute { listener.onComplete(task) } }
        }
        return Triple(task, succeed, fail)
    }

    // Play pack release on normal uninstall and preventing pack resurrection

    @Test
    fun `normal Play uninstall releases the delivered asset pack`() {
        file("usr/opt/java/bin/java", "java-bin")
        stateFile.writeText("""[{"name":"java","installRoot":"usr/opt/java"}]""")
        assertFalse(installMarkerFile("toolchain_java").exists())

        uninstallSync("java")

        verify(exactly = 1) { packManager.removePack("toolchain_java") }
        assertEquals(emptyList<String>(), manager().getInstalledToolchains())
        assertEquals(listOf(AssetPackStatus.NOT_INSTALLED to null), reports)
    }

    @Test
    fun `reconcile retries pack release instead of resurrecting an uninstalled toolchain`() {
        file("usr/opt/java/bin/java", "java-bin")
        stateFile.writeText("""[{"name":"java","installRoot":"usr/opt/java"}]""")
        val delivered = deliverPack("toolchain_java", "java")
        playHolds("toolchain_java", delivered)

        val (firstTask, _, failFirst) = controllableRemovalTask()
        val (secondTask, succeedSecond, _) = controllableRemovalTask()
        every { packManager.removePack("toolchain_java") } returnsMany listOf(firstTask, secondTask)

        uninstallSync("java")
        verify(exactly = 1) { packManager.removePack("toolchain_java") }
        failFirst(IllegalStateException("Play busy"))

        val nextLaunch = manager()
        nextLaunch.reconcileDeliveredPacks()
        awaitLaunchPass(nextLaunch)

        verify(exactly = 2) { packManager.removePack("toolchain_java") }
        assertFalse(
            "java" in nextLaunch.getInstalledToolchains(),
            "reconcile silently reinstalled a toolchain the user had uninstalled",
        )
        assertFalse(
            File(filesDir, "usr/opt/java/bin/java").exists(),
            "reconcile copied files back for an uninstalled toolchain",
        )

        succeedSecond()
        every { packManager.getPackLocation("toolchain_java") } returns null

        // Fresh install clears any marker and allows normal delivery reconciliation again.
        playHolds("toolchain_java", delivered)
        val fresh = manager()
        fresh.install("toolchain_java")
        fresh.reconcileDeliveredPacks()
        awaitLaunchPass(fresh)

        assertTrue("java" in fresh.getInstalledToolchains(), "explicit reinstall should reconcile cleanly")
    }

    // Base library listing preflight and state reconciliation on partial uninstall failure

    @Test
    fun `uninstall with unreadable base lib listing leaves binaries and install root intact`() {
        val bin = file("usr/bin/ruby", "ruby-bin")
        val sym = link("usr/bin/rb", "usr/bin/ruby")
        val rootFile = file("usr/lib/ruby/core.rb", "core")
        val lib = file("usr/lib/libruby.so", "lib")
        envFile.writeText("export RUBY_TEST=1\n")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/lib/ruby","binaries":["usr/bin/ruby"],""" +
                """"symlinks":{"rb":"usr/bin/ruby"},"libs":["libruby.so"]}]""",
        )

        for (behavior in listOf<() -> Array<String>?>(
            { throw IOException("assets unavailable") },
            { null },
        )) {
            reports.clear()
            every { assets.list("usr/lib") } answers { behavior() }

            uninstallSync("ruby")

            assertTrue(bin.exists(), "binary was deleted before base lib listing preflight")
            assertTrue(Files.isSymbolicLink(sym.toPath()), "symlink was deleted before base lib listing preflight")
            assertTrue(rootFile.exists(), "installRoot was deleted before base lib listing preflight")
            assertTrue(lib.exists(), "library was deleted when base lib listing failed")
            assertEquals(listOf("ruby"), manager().getInstalledToolchains())
            assertEquals(listOf(AssetPackStatus.UNKNOWN to ToolchainFailure.REMOVAL), reports)
        }
    }

    @Test
    fun `partial uninstall failure reconciles state and regenerates env files`() {
        installedBy("com.example.sideloader")
        val bin = file("usr/bin/ruby", "ruby-bin")
        // A non-empty directory at a symlink name cannot be deleted by File.delete(), forcing partial deletion
        // after usr/bin/ruby is already gone.
        val stuck = file("usr/bin/rb/cannot-unlink.txt", "keep")
        envFile.writeText("export RUBY_TEST=1\n")
        stateFile.writeText(
            """[{"name":"ruby","binaries":["usr/bin/ruby"],"symlinks":{"rb":"usr/bin/ruby"}}]""",
        )

        uninstallSync("ruby")

        assertFalse(bin.exists(), "fixture premise: binary should have been deleted before failure")
        assertTrue(stuck.exists(), "fixture premise: stuck directory remains on disk")
        assertEquals(
            emptyList<String>(),
            manager().getInstalledToolchains(),
            "toolchains.json still claims a partially deleted toolchain is installed",
        )
        assertFalse(envFile.exists(), "toolchain-env.sh still exports a partially deleted toolchain")
        assertTrue(
            installMarkerFile("toolchain_ruby").isFile,
            "leftover files should retain a cleanup marker",
        )
        assertEquals(
            listOf(AssetPackStatus.FAILED to ToolchainFailure.REMOVAL),
            reports,
            "partial uninstall should report FAILED so Retry reinstalls cleanly",
        )
    }

    // Cross-root toolchain upgrade failure recovery

    @Test
    fun `crashed cross-root upgrade reclaims only orphaned paths and unblocks a newer root`() {
        installedBy("com.example.sideloader")
        val java21Root = "usr/lib/jvm/java-21-openjdk"
        val java25Root = "usr/lib/jvm/java-25-openjdk"
        val java26Root = "usr/lib/jvm/java-26-openjdk"

        file("$java21Root/bin/java", "java-21")
        link("usr/bin/java", "$java21Root/bin/java")
        file("usr/lib/libandroid-shmem.so", "shmem")
        file("usr/lib/libshared.so", "ruby-shared")
        file("usr/lib/libbase.so", "base-lib")

        stateFile.writeText(
            """[
                {"name":"java","displayName":"Java 21","installRoot":"$java21Root","installId":"id-21",
                 "binaries":["$java21Root/bin/java"],
                 "symlinks":{"java":"$java21Root/bin/java"},
                 "libs":["libandroid-shmem.so"]},
                {"name":"ruby","installRoot":"usr/lib/ruby","libs":["libshared.so"]}
            ]""".trimIndent(),
        )

        // Crashed java-25 upgrade left files and pending marker with a different installId.
        file("$java25Root/bin/java", "java-25-partial")
        file("usr/share/doc/openjdk-25/LICENSE", "license")
        file("usr/lib/libonly25.so", "only-25")
        link("usr/bin/java25only", "$java25Root/bin/java")
        installMarkerFile("toolchain_java").apply {
            parentFile?.mkdirs()
            writeText(
                """{"name":"java","displayName":"Java 25","installRoot":"$java25Root","installId":"id-25",
                    "binaries":["$java25Root/bin/java"],
                    "symlinks":{"java":"$java25Root/bin/java","java25only":"$java25Root/bin/java"},
                    "ownedPaths":["usr/share/doc/openjdk-25"],
                    "libs":["libandroid-shmem.so","libshared.so","libbase.so","libonly25.so"]}""".trimIndent(),
            )
        }

        // First verify launch reclaim removes only java-25 orphans while preserving java-21, ruby, and base libs.
        reclaimInterrupted()

        assertFalse(existsNoFollow(java25Root), "orphaned java-25 root was not reclaimed")
        assertFalse(existsNoFollow("usr/share/doc/openjdk-25"), "orphaned java-25 owned path was not reclaimed")
        assertFalse(existsNoFollow("usr/lib/libonly25.so"), "orphaned java-25 lib was not reclaimed")
        assertFalse(existsNoFollow("usr/bin/java25only"), "orphaned java-25 symlink was not reclaimed")
        assertFalse(installMarkerFile("toolchain_java").exists(), "stale pending marker should be cleared")
        assertEquals("java-21", File(filesDir, "$java21Root/bin/java").readText())
        assertTrue(existsNoFollow("usr/bin/java"), "installed java-21 symlink was deleted")
        assertTrue(existsNoFollow("usr/lib/libandroid-shmem.so"), "installed java-21 lib was deleted")
        assertTrue(existsNoFollow("usr/lib/libshared.so"), "ruby shared lib was deleted")
        assertTrue(existsNoFollow("usr/lib/libbase.so"), "base lib was deleted")

        // Now re-plant the crashed java-25 marker and verify a java-26 install is NOT blocked by it.
        file("$java25Root/bin/java", "java-25-partial")
        installMarkerFile("toolchain_java").writeText(
            """{"name":"java","displayName":"Java 25","installRoot":"$java25Root","installId":"id-25",
                "binaries":["$java25Root/bin/java"]}""".trimIndent(),
        )
        val java26Pack = File(filesDir, "pack-java26").apply {
            mkdirs()
            File(this, "toolchain_java.json").writeText(
                """{"name":"java","displayName":"Java 26","installRoot":"$java26Root",
                    "binaries":["$java26Root/bin/java"],
                    "symlinks":{"java":"$java26Root/bin/java"}}""".trimIndent(),
            )
            File(this, "$java26Root/bin").mkdirs()
            File(this, "$java26Root/bin/java").writeText("java-26")
        }

        reports.clear()
        val ok = installFromDirectory(manager(), "toolchain_java", java26Pack)

        assertTrue(ok, "java-26 install was blocked by crashed java-25 marker: $reports")
        assertEquals(listOf(AssetPackStatus.COMPLETED to null), reports)
        assertFalse(existsNoFollow(java25Root), "java-25 orphan survived java-26 install")
        assertFalse(existsNoFollow(java21Root), "superseded java-21 root survived java-26 install")
        assertEquals("java-26", File(filesDir, "$java26Root/bin/java").readText())
    }

    // Serializing interrupted install reclaim with active HTTP downloads and responsive cancel

    @Test
    fun `reclaimInterruptedInstallsSync skips a pack with an active HTTP download`() {
        val partial = file("usr/opt/java/bin/java", "partial")
        val marker = installMarkerFile("toolchain_java").apply {
            parentFile?.mkdirs()
            writeText("""{"name":"java","installRoot":"usr/opt/java"}""")
        }
        httpDownloadsMap()["toolchain_java"] = newDownloadToken()

        reclaimInterrupted()

        assertTrue(partial.exists(), "reclaim deleted files while an HTTP download was active")
        assertTrue(marker.exists(), "reclaim deleted pending marker while an HTTP download was active")
    }

    @Test
    fun `cancelling during retry backoff wakes immediately without issuing another request`() {
        installedBy("com.example.sideloader")
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { sock ->
                        val line = sock.getInputStream().bufferedReader().readLine() ?: return@use
                        requests.add(line.split(" ").getOrElse(1) { "/" })
                        sock.getOutputStream().write(
                            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(),
                        )
                        sock.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                    if (server.isClosed) return@Thread
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val zipUrl = "http://127.0.0.1:${server.localPort}/download/toolchain_test.zip"
            stubSingleToolchain(zipUrl)

            val enteringBackoff = CountDownLatch(1)
            every { Logger.i(any(), match { it.startsWith("Retry 1/") }) } answers {
                enteringBackoff.countDown()
            }

            val canceled = CountDownLatch(1)
            val m = manager()
            m.onStateChange = { _, status, _, why, _ ->
                reports.add(status to why)
                if (status == AssetPackStatus.CANCELED || status == AssetPackStatus.FAILED) {
                    canceled.countDown()
                }
            }

            m.install("toolchain_test")
            assertTrue(enteringBackoff.await(10, TimeUnit.SECONDS), "never reached retry backoff")

            val cancelAt = System.nanoTime()
            m.cancel("toolchain_test")
            assertTrue(canceled.await(5, TimeUnit.SECONDS), "cancelled download did not settle")
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt)

            assertTrue(
                elapsedMs < 1_000,
                "cancel waited ${elapsedMs}ms during retry backoff instead of waking immediately",
            )
            assertEquals(
                1,
                requests.count { it == "/download/toolchains.sha256" },
                "cancel during retry backoff still fired another manifest request: $requests",
            )
            assertTrue(reports.any { it.first == AssetPackStatus.CANCELED }, "expected CANCELED: $reports")
        } finally {
            server.close()
            serverThread.join(2_000)
        }
    }

    @Test
    fun `cancelling while fetchManifest is stalled disconnects immediately`() {
        installedBy("com.example.sideloader")
        val manifestAccepted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        val serverThread = Thread {
            while (!server.isClosed) {
                try {
                    val sock = server.accept()
                    sockets.add(sock)
                    sock.soTimeout = 10_000
                    sock.getInputStream().bufferedReader().readLine()
                    manifestAccepted.countDown()
                    releaseServer.await(10, TimeUnit.SECONDS)
                    runCatching { sock.close() }
                } catch (_: Exception) {
                    if (server.isClosed) return@Thread
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val zipUrl = "http://127.0.0.1:${server.localPort}/download/toolchain_test.zip"
            stubSingleToolchain(zipUrl)

            val canceled = CountDownLatch(1)
            val m = manager()
            m.onStateChange = { _, status, _, why, _ ->
                reports.add(status to why)
                if (status == AssetPackStatus.CANCELED || status == AssetPackStatus.FAILED) {
                    canceled.countDown()
                }
            }

            m.install("toolchain_test")
            assertTrue(manifestAccepted.await(5, TimeUnit.SECONDS), "manifest request was never received")

            val cancelAt = System.nanoTime()
            m.cancel("toolchain_test")
            assertTrue(canceled.await(3, TimeUnit.SECONDS), "stalled fetchManifest did not abort on cancel")
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt)

            assertTrue(elapsedMs < 2_000, "stalled fetchManifest took ${elapsedMs}ms to cancel")
            assertTrue(reports.any { it.first == AssetPackStatus.CANCELED }, "expected CANCELED: $reports")
        } finally {
            releaseServer.countDown()
            sockets.toList().forEach { runCatching { it.close() } }
            server.close()
            serverThread.join(2_000)
        }
    }

    // Shell function naming, install path confinement, and shared ownership on uninstall

    @Test
    fun `isShellFunctionName rejects bash reserved words, pure digits, control chars, and leading hash comments`() {
        val reserved = listOf(
            "if", "then", "else", "elif", "fi",
            "case", "esac", "for", "select", "while", "until", "do", "done", "in",
            "function", "time", "coproc",
            "{", "}", "!", "[[", "]]",
            "#", "#comment", "#ruby",
            "0", "123", "42",
            "\u0001", "a\u0000b", "a\u007fb",
        )
        for (bad in reserved) {
            assertFalse(isShellFunctionName(bad), "'$bad' must not be accepted as a shell function name")
        }
        val legitimate = listOf(
            "ruby", "gem", "rake", "irb", "bundle", "rdoc",
            "java", "javac", "jar", "jshell", "keytool",
            "clang", "clang++", "g++", "c++", "cc", "lld", "clangd",
            "aarch64-linux-android-clang", "aarch64-linux-android-clang++",
            "codex", "a#b", "2to3", "-flag",
        )
        for (good in legitimate) {
            assertTrue(isShellFunctionName(good), "'$good' should remain a valid shell function name")
        }
    }

    @Test
    fun `install refuses escaping binaries, symlinks, and libSymlinks and applyManifestExecBits stays confined`() {
        installedBy("com.example.sideloader")
        val secret = file("home/secret.sh", "do-not-touch")
        secret.setExecutable(false, false)

        // 1. Escaping symlink link location
        val escapeLinkPack = File(filesDir, "pack-escape-link").apply {
            mkdirs()
            File(this, "toolchain_java.json").writeText(
                """{"name":"java","installRoot":"usr/opt/java","symlinks":{"../../home/pwned":"usr/opt/java/bin/java"}}""",
            )
            File(this, "usr/opt/java/bin").mkdirs()
            File(this, "usr/opt/java/bin/java").writeText("java")
        }
        reports.clear()
        installFromDirectory(manager(), "toolchain_java", escapeLinkPack)
        assertFalse(existsNoFollow("home/pwned"), "install created a symlink outside usr/")
        assertFalse(existsNoFollow("usr/opt/java"), "rejected install left partial copy behind")
        assertTrue(reports.any { it.first == AssetPackStatus.FAILED })

        // 2. Escaping symlink target
        val escapeTargetPack = File(filesDir, "pack-escape-target").apply {
            mkdirs()
            File(this, "toolchain_java.json").writeText(
                """{"name":"java","installRoot":"usr/opt/java","symlinks":{"java":"home/secret.sh"}}""",
            )
            File(this, "usr/opt/java/bin").mkdirs()
            File(this, "usr/opt/java/bin/java").writeText("java")
        }
        reports.clear()
        installFromDirectory(manager(), "toolchain_java", escapeTargetPack)
        assertFalse(existsNoFollow("usr/bin/java"), "install created symlink pointing outside usr/")
        assertFalse(existsNoFollow("usr/opt/java"), "rejected install left partial copy behind")
        assertTrue(reports.any { it.first == AssetPackStatus.FAILED })

        // 3. Escaping libSymlinks target
        val escapeLibPack = File(filesDir, "pack-escape-lib").apply {
            mkdirs()
            File(this, "toolchain_java.json").writeText(
                """{"name":"java","installRoot":"usr/opt/java","libSymlinks":{"libevil.so.1":"../../home/secret.sh"}}""",
            )
            File(this, "usr/opt/java/bin").mkdirs()
            File(this, "usr/opt/java/bin/java").writeText("java")
        }
        reports.clear()
        installFromDirectory(manager(), "toolchain_java", escapeLibPack)
        assertFalse(existsNoFollow("usr/lib/libevil.so.1"), "install created lib symlink pointing outside usr/")
        assertFalse(existsNoFollow("usr/opt/java"), "rejected install left partial copy behind")
        assertTrue(reports.any { it.first == AssetPackStatus.FAILED })

        // 4. Escaping binary in applyManifestExecBits (via repairInstalledToolchainsSync)
        stateFile.writeText("""[{"name":"java","binaries":["home/secret.sh","../outside"]}]""")
        repairSync()
        assertFalse(secret.canExecute(), "applyManifestExecBits set execute bit outside usr/")
    }

    @Test
    fun `uninstall preserves symlinks, libSymlinks, and binaries shared with another toolchain`() {
        installedBy("com.example.sideloader")
        file("usr/opt/ruby/bin/tool", "ruby-tool")
        file("usr/opt/java/bin/tool", "java-tool")
        link("usr/bin/shared-cmd", "usr/opt/java/bin/tool")
        link("usr/bin/ruby-only", "usr/opt/ruby/bin/tool")
        file("usr/lib/libshared.so", "shared-lib")
        file("usr/lib/libruby.so", "ruby-lib")
        link("usr/lib/libshared.so.1", "usr/lib/libshared.so")
        link("usr/lib/libruby.so.4", "usr/lib/libruby.so")
        file("usr/bin/shared-bin", "shared-bin")
        file("usr/bin/ruby-bin", "ruby-bin")

        stateFile.writeText(
            """[
                {"name":"ruby","installRoot":"usr/opt/ruby",
                 "binaries":["usr/bin/shared-bin","usr/bin/ruby-bin"],
                 "symlinks":{"shared-cmd":"usr/opt/ruby/bin/tool","ruby-only":"usr/opt/ruby/bin/tool"},
                 "libs":["libshared.so","libruby.so"],
                 "libSymlinks":{"libshared.so.1":"libshared.so","libruby.so.4":"libruby.so"}},
                {"name":"java","installRoot":"usr/opt/java",
                 "binaries":["usr/bin/shared-bin"],
                 "symlinks":{"shared-cmd":"usr/opt/java/bin/tool"},
                 "libs":["libshared.so"],
                 "libSymlinks":{"libshared.so.1":"libshared.so"}}
            ]""".trimIndent(),
        )

        uninstallSync("ruby")

        assertTrue(existsNoFollow("usr/bin/shared-cmd"), "shared symlink was deleted by ruby uninstall")
        assertTrue(existsNoFollow("usr/lib/libshared.so.1"), "shared lib symlink was deleted by ruby uninstall")
        assertTrue(existsNoFollow("usr/bin/shared-bin"), "shared binary was deleted by ruby uninstall")
        assertTrue(existsNoFollow("usr/lib/libshared.so"), "shared lib was deleted by ruby uninstall")
        assertFalse(existsNoFollow("usr/bin/ruby-only"), "ruby-only symlink was not deleted")
        assertFalse(existsNoFollow("usr/lib/libruby.so.4"), "ruby-only lib symlink was not deleted")
        assertFalse(existsNoFollow("usr/bin/ruby-bin"), "ruby-only binary was not deleted")
        assertFalse(existsNoFollow("usr/lib/libruby.so"), "ruby-only lib was not deleted")
        assertEquals(listOf("java"), manager().getInstalledToolchains())
        assertEquals(listOf(AssetPackStatus.NOT_INSTALLED to null), reports)
    }

    // Redirect URL resolution, host allowlist, and cancellable size-bounded zip extraction

    @Test
    fun `nextRedirectUrl resolves relative paths starting with http and enforces GitHub host allowlist`() {
        assertEquals(
            "https://github.com/rmyndharis/VSCodroid/releases/download/v1/http-archive.zip",
            nextRedirectUrl(
                "https://github.com/rmyndharis/VSCodroid/releases/download/v1/toolchain_java.zip",
                "http-archive.zip",
            ),
        )
        assertEquals(
            "https://release-assets.githubusercontent.com/signed/asset.zip",
            nextRedirectUrl(
                "https://github.com/rmyndharis/VSCodroid/releases/download/v1/toolchain_java.zip",
                "https://release-assets.githubusercontent.com/signed/asset.zip",
            ),
        )
        assertEquals(
            "https://objects.githubusercontent.com:443/signed/asset.zip",
            nextRedirectUrl(
                "https://github.com/rmyndharis/VSCodroid/releases/download/v1/toolchain_java.zip",
                "https://objects.githubusercontent.com:443/signed/asset.zip",
            ),
        )
        for (bad in listOf(
            "https://evil.example/toolchain_java.zip",
            "https://github.com.evil.example/toolchain_java.zip",
            "https://evilgithubusercontent.com/toolchain_java.zip",
            "https://..githubusercontent.com/toolchain_java.zip",
            "https://.githubusercontent.com/toolchain_java.zip",
            "https://objects.githubusercontent.com:8443/signed/asset.zip",
            "//evil.example/toolchain_java.zip",
            "file:///etc/passwd",
        )) {
            assertThrows(
                IOException::class.java,
                {
                    nextRedirectUrl(
                        "https://github.com/rmyndharis/VSCodroid/releases/download/v1/toolchain_java.zip",
                        bad,
                    )
                },
                "redirect to '$bad' should have been refused",
            )
        }
    }

    @Test
    fun `cancelling mid-extraction stops extracting further zip entries`() {
        installedBy("com.example.sideloader")
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("toolchain_test.json"))
                zip.write("""{"name":"test","installRoot":"usr/opt/test"}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("usr/opt/test/first.bin"))
                zip.write(ByteArray(32 * 1024) { 1 })
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("usr/opt/test/second.bin"))
                zip.write(ByteArray(32 * 1024) { 2 })
                zip.closeEntry()
            }
        }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(zipBytes)
            .joinToString("") { "%02x".format(it) }

        val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val routes = mapOf(
            "/download/toolchain_test.zip" to zipBytes,
            "/download/toolchains.sha256" to "$digest  toolchain_test.zip\n".toByteArray(),
        )
        val serverThread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { sock ->
                        val line = sock.getInputStream().bufferedReader().readLine() ?: return@use
                        val path = line.split(" ").getOrElse(1) { "/" }
                        val body = routes[path]
                        val out = sock.getOutputStream()
                        if (body == null) {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            out.write(
                                "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray(),
                            )
                            out.write(body)
                        }
                        out.flush()
                    }
                } catch (_: Exception) {
                    if (server.isClosed) return@Thread
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val zipUrl = "http://127.0.0.1:${server.localPort}/download/toolchain_test.zip"
            stubSingleToolchain(zipUrl, zipBytes.size.toLong())

            val m = manager()
            val settled = CountDownLatch(1)
            m.onStateChange = { _, status, _, why, _ ->
                reports.add(status to why)
                if (status == AssetPackStatus.CANCELED || status == AssetPackStatus.FAILED ||
                    status == AssetPackStatus.COMPLETED
                ) {
                    settled.countDown()
                }
            }

            var sawSecondEntry = false
            mockkConstructor(ZipInputStream::class)
            every { anyConstructed<ZipInputStream>().nextEntry } answers {
                (callOriginal() as ZipEntry?).also { entry ->
                    if (entry?.name == "usr/opt/test/first.bin") {
                        m.cancel("toolchain_test")
                    } else if (entry?.name == "usr/opt/test/second.bin") {
                        sawSecondEntry = true
                    }
                }
            }

            m.install("toolchain_test")
            assertTrue(settled.await(15, TimeUnit.SECONDS), "install never settled: $reports")

            assertFalse(sawSecondEntry, "extractZip kept reading entries after cancellation")
            assertTrue(reports.any { it.first == AssetPackStatus.CANCELED }, "expected CANCELED: $reports")
            assertFalse(reports.any { it.first == AssetPackStatus.FAILED }, "cancel reported FAILED: $reports")
        } finally {
            server.close()
            serverThread.join(2_000)
        }
    }

    private fun stubSingleToolchain(zipUrl: String, size: Long = 1_024L) {
        val fixture = ToolchainRegistry.ToolchainInfo(
            packName = "toolchain_test",
            displayName = "Test",
            shortLabel = "Test",
            descriptionRes = com.vscodroid.R.string.toolchain_ruby_description,
            estimatedSize = size,
            downloadSize = size,
            downloadUrl = zipUrl,
        )
        mockkObject(ToolchainRegistry)
        every { ToolchainRegistry.available } returns listOf(fixture)
        every { ToolchainRegistry.find(any()) } answers {
            if (firstArg<String>() in setOf("toolchain_test", "test")) fixture else null
        }
    }

    @Test
    fun `reconcile still installs an unconsumed delivery when no uninstall occurred`() {
        val delivered = deliverPack("toolchain_java", "java")
        playHolds("toolchain_java", delivered)

        val m = manager()
        m.reconcileDeliveredPacks()
        awaitLaunchPass(m)

        assertTrue("java" in m.getInstalledToolchains(), "failed-install retry without uninstall should install")
        assertTrue(File(filesDir, "usr/opt/java/bin/java").isFile)
        verify(exactly = 1) { packManager.removePack("toolchain_java") }
    }

    @Test
    fun `install and applyManifestExecBits refuse a redirected usr bin ancestor`() {
        installedBy("com.example.sideloader")
        val targetFile = file("home/project/ruby", "project-file")
        targetFile.setExecutable(false, false)
        link("usr/bin", "home/project")

        val pack = File(filesDir, "pack-redirected").apply {
            mkdirs()
            File(this, "toolchain_ruby.json").writeText(
                """{"name":"ruby","installRoot":"usr/lib/ruby","binaries":["usr/bin/ruby"],""" +
                    """"symlinks":{"rb":"usr/bin/ruby"}}""",
            )
            File(this, "usr/bin").mkdirs()
            File(this, "usr/bin/ruby").writeText("ruby")
            File(this, "usr/lib/ruby").mkdirs()
        }

        reports.clear()
        installFromDirectory(manager(), "toolchain_ruby", pack)

        assertEquals("project-file", targetFile.readText(), "install overwrote file through redirected usr/bin")
        assertFalse(existsNoFollow("home/project/rb"), "install wrote symlink through redirected usr/bin")
        assertTrue(reports.any { it.first == AssetPackStatus.FAILED })

        stateFile.writeText("""[{"name":"ruby","binaries":["usr/bin/ruby"]}]""")
        repairSync()
        assertFalse(targetFile.canExecute(), "applyManifestExecBits set execute bit through redirected usr/bin")
    }

    @Test
    fun `extractToolchainZip enforces byte limit, entry limit, and cleans up on cancel`() {
        val destBytes = File(filesDir, "extract-bytes").apply { mkdirs() }
        val bigZip = File(filesDir, "big.zip")
        ZipOutputStream(bigZip.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("usr/big.bin"))
            zip.write(ByteArray(32 * 1024))
            zip.closeEntry()
        }
        assertThrows(SecurityException::class.java) {
            extractToolchainZip(bigZip, destBytes, appRoot = filesDir, maxBytes = 8 * 1024)
        }
        assertFalse(File(destBytes, "usr/big.bin").exists(), "oversized extraction left partial file behind")

        val destEntries = File(filesDir, "extract-entries").apply { mkdirs() }
        val manyZip = File(filesDir, "many.zip")
        ZipOutputStream(manyZip.outputStream()).use { zip ->
            for (i in 1..15) {
                zip.putNextEntry(ZipEntry("usr/file-$i.txt"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        assertThrows(SecurityException::class.java) {
            extractToolchainZip(manyZip, destEntries, appRoot = filesDir, maxEntries = 10)
        }
        assertFalse(File(destEntries, "usr/file-1.txt").exists(), "entry-limit violation left partial files behind")

        val destCancel = File(filesDir, "extract-cancel").apply { mkdirs() }
        var checks = 0
        assertThrows(IOException::class.java) {
            extractToolchainZip(bigZip, destCancel, appRoot = filesDir, isCancelled = { ++checks >= 3 })
        }
        assertFalse(File(destCancel, "usr/big.bin").exists(), "cancelled extraction left partial file behind")

        val destOk = File(filesDir, "extract-ok").apply { mkdirs() }
        extractToolchainZip(bigZip, destOk, appRoot = filesDir, maxBytes = 64 * 1024, maxEntries = 10)
        assertEquals(32 * 1024L, File(destOk, "usr/big.bin").length())

        for (badName in listOf("", ".")) {
            val destBad = File(filesDir, "extract-bad-${badName.length}").apply { mkdirs() }
            val badZip = File(filesDir, "bad-${badName.length}.zip")
            ZipOutputStream(badZip.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(badName))
                zip.write("boom".toByteArray())
                zip.closeEntry()
            }
            assertThrows(
                SecurityException::class.java,
                { extractToolchainZip(badZip, destBad, appRoot = filesDir) },
                "non-directory entry '$badName' resolving to destDir must throw SecurityException",
            )
        }

        // Pre-existing symlink target inside destDir must be refused rather than written through.
        val destSym = File(filesDir, "extract-sym").apply { mkdirs() }
        val symTarget = File(destSym, "usr/real.txt").apply {
            parentFile?.mkdirs()
            writeText("original")
        }
        Files.createSymbolicLink(File(destSym, "usr/link.txt").toPath(), symTarget.toPath())
        val symZip = File(filesDir, "sym.zip")
        ZipOutputStream(symZip.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("usr/link.txt"))
            zip.write("overwritten".toByteArray())
            zip.closeEntry()
        }
        val symError = assertThrows(SecurityException::class.java) {
            extractToolchainZip(symZip, destSym, appRoot = filesDir)
        }
        assertTrue(
            symError.message?.contains("symlink", ignoreCase = true) == true,
            "expected symlink refusal, got: ${symError.message}",
        )
        assertFalse(destSym.exists(), "failed symlink extraction did not clean up staging dir")

        val destSymOut = File(filesDir, "extract-sym-out").apply { mkdirs() }
        val outsideTarget = file("home/outside-target.txt", "original")
        File(destSymOut, "usr").mkdirs()
        Files.createSymbolicLink(File(destSymOut, "usr/link.txt").toPath(), outsideTarget.toPath())
        assertThrows(SecurityException::class.java) {
            extractToolchainZip(symZip, destSymOut, appRoot = filesDir)
        }
        assertEquals("original", outsideTarget.readText(), "zip extraction wrote through an out-of-tree symlink")

        // Truncated zip throws IOException and must clean up already extracted files.
        val destTrunc = File(filesDir, "extract-trunc").apply { mkdirs() }
        val incompressible = ByteArray(16 * 1024).also { java.util.Random(42L).nextBytes(it) }
        val validBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("usr/first.txt"))
                zip.write("first-entry".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("usr/second.txt"))
                zip.write(incompressible)
                zip.closeEntry()
            }
        }.toByteArray()
        val truncZip = File(filesDir, "trunc.zip").apply {
            writeBytes(validBytes.copyOf(validBytes.size / 2))
        }
        assertThrows(IOException::class.java) {
            extractToolchainZip(truncZip, destTrunc, appRoot = filesDir)
        }
        assertFalse(
            File(destTrunc, "usr/first.txt").exists(),
            "IOException during zip extraction left earlier extracted files behind",
        )
    }

    @Test
    fun `immediate install after NOT_INSTALLED drops deferred removePack`() {
        file("usr/opt/java/bin/java", "java-bin")
        stateFile.writeText("""[{"name":"java","installRoot":"usr/opt/java"}]""")
        every { packManager.fetch(any()) } returns mockk(relaxed = true)

        val m = manager()
        m.onStateChange = { _, status, _, why, _ ->
            reports.add(status to why)
            if (status == AssetPackStatus.NOT_INSTALLED) {
                m.install("toolchain_java")
            }
        }

        uninstallSync("java", m)

        verify(exactly = 1) { packManager.fetch(listOf("toolchain_java")) }
        verify(exactly = 0) { packManager.removePack("toolchain_java") }
        assertFalse(releaseMarkerFile("toolchain_java").exists(), "install did not clear the pending release marker")
    }

    @Test
    fun `delayed completion of an older removePack does not clear a newer uninstall marker or race reconcile`() {
        val (firstTask, succeedFirst, _) = controllableRemovalTask()
        val (secondTask, succeedSecond, _) = controllableRemovalTask()
        every { packManager.removePack("toolchain_java") } returnsMany listOf(firstTask, secondTask)

        // Start an older removePack that stays in flight.
        val canceller = manager()
        canceller.cancel("toolchain_java")
        ioExecutorOf(canceller).submit {}.get(5, TimeUnit.SECONDS)
        verify(exactly = 1) { packManager.removePack("toolchain_java") }

        // Uninstall while the older removePack is still running.
        file("usr/opt/java/bin/java", "java-bin")
        stateFile.writeText("""[{"name":"java","installRoot":"usr/opt/java"}]""")
        uninstallSync("java")
        assertTrue(releaseMarkerFile("toolchain_java").exists(), "uninstall did not write pending release marker")

        // The older removePack succeeds later; it must not clear the newer uninstall's marker.
        succeedFirst()
        assertTrue(
            releaseMarkerFile("toolchain_java").exists(),
            "delayed completion of an older removePack wiped a newer uninstall marker",
        )

        // Reconcile sees Play still holding the pack and retries removePack instead of reinstalling.
        val delivered = deliverPack("toolchain_java", "java")
        playHolds("toolchain_java", delivered)
        val nextLaunch = manager()
        nextLaunch.reconcileDeliveredPacks()
        awaitLaunchPass(nextLaunch)

        verify(exactly = 2) { packManager.removePack("toolchain_java") }
        assertFalse("java" in nextLaunch.getInstalledToolchains(), "reconcile resurrected an uninstalled pack")
        assertTrue(releaseMarkerFile("toolchain_java").exists(), "marker cleared before retry removePack succeeded")

        succeedSecond()
        assertFalse(releaseMarkerFile("toolchain_java").exists(), "marker remained after retry removePack succeeded")

        // Invalid pack names with path separators or traversal must be rejected.
        val markerMethod = ToolchainManager::class.java
            .getDeclaredMethod("packReleaseMarker", String::class.java)
            .apply { isAccessible = true }
        val markMethod = ToolchainManager::class.java
            .getDeclaredMethod("markPackReleasePending", String::class.java)
            .apply { isAccessible = true }
        val checker = manager()
        for (badPack in listOf("../escape", "a/b", "..", ".", "")) {
            assertNull(markerMethod.invoke(checker, badPack), "packReleaseMarker accepted '$badPack'")
        }
        markMethod.invoke(checker, "../escape")
        assertFalse(File(filesDir, "home/.vscodroid/escape").exists(), "invalid pack name wrote outside marker dir")
    }

    @Test
    fun `partial uninstall reclaims a prior cross-root install marker and refuses to overwrite an unsettled one`() {
        installedBy("com.example.sideloader")

        // 1. Settleable prior cross-root marker is reclaimed before recording the partial uninstall marker.
        file("usr/bin/ruby", "ruby-bin")
        file("usr/bin/rb/cannot-unlink.txt", "keep")
        file("usr/opt/ruby-next/bin/ruby", "ruby-next")
        stateFile.writeText("""[{"name":"ruby","binaries":["usr/bin/ruby"],"symlinks":{"rb":"usr/bin/ruby"}}]""")
        installMarkerFile("toolchain_ruby").apply {
            parentFile?.mkdirs()
            writeText(
                """{"name":"ruby","installRoot":"usr/opt/ruby-next","installId":"id-next",""" +
                    """"binaries":["usr/opt/ruby-next/bin/ruby"],"symlinks":{"rb":"usr/opt/ruby-next/bin/ruby"}}""",
            )
        }

        uninstallSync("ruby")

        assertFalse(existsNoFollow("usr/opt/ruby-next"), "prior cross-root partial copy was not reclaimed")
        assertEquals(emptyList<String>(), manager().getInstalledToolchains())
        assertTrue(installMarkerFile("toolchain_ruby").isFile)
        assertFalse(
            installMarkerFile("toolchain_ruby").readText().contains("ruby-next"),
            "cleanup marker should describe the partially removed install after reclaiming prior marker",
        )
        assertFalse(
            Files.isSymbolicLink(File(filesDir, "usr/bin/rb").toPath()),
            "partial uninstall recreated a symlink it was removing",
        )
        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.REMOVAL), reports)

        // 2. Unreadable prior marker must not be overwritten and state entry must stay intact.
        reports.clear()
        file("usr/bin/ruby", "ruby-bin")
        stateFile.writeText("""[{"name":"ruby","binaries":["usr/bin/ruby"],"symlinks":{"rb":"usr/bin/ruby"}}]""")
        installMarkerFile("toolchain_ruby").writeText("{corrupt")

        uninstallSync("ruby")

        assertEquals(listOf("ruby"), manager().getInstalledToolchains(), "state was dropped over an unsettled marker")
        assertEquals("{corrupt", installMarkerFile("toolchain_ruby").readText(), "prior marker was overwritten")
        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.REMOVAL), reports)
    }

    @Test
    fun `partial uninstall keeps state on cleanup marker write failure and rolls back on state write failure`() {
        mockkStatic(Files::class)
        installedBy("com.android.vending")

        // 1. Failing to write the cleanup marker leaves the state record intact and reports STORAGE.
        file("usr/bin/ruby", "ruby-bin")
        file("usr/bin/rb/cannot-unlink.txt", "keep")
        stateFile.writeText("""[{"name":"ruby","binaries":["usr/bin/ruby"],"symlinks":{"rb":"usr/bin/ruby"}}]""")
        val markerBlock = blockAtomicWritesTo(installMarkerFile("toolchain_ruby"))

        uninstallSync("ruby")

        assertTrue(markerBlock.attempts.get() >= 1, "marker write was not attempted")
        assertEquals(listOf("ruby"), manager().getInstalledToolchains(), "state was dropped after marker write failed")
        assertFalse(installMarkerFile("toolchain_ruby").exists())
        assertFalse(releaseMarkerFile("toolchain_ruby").exists())
        verify(exactly = 0) { packManager.removePack("toolchain_ruby") }
        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.STORAGE), reports)
        markerBlock.enabled.set(false)

        // 2. Failing writeState deletes the newly written cleanup marker and rolls back the release marker.
        reports.clear()
        file("usr/bin/ruby", "ruby-bin")
        val stateBlock = blockAtomicWritesTo(stateFile)

        uninstallSync("ruby")

        assertTrue(stateBlock.attempts.get() >= 1, "state write was not attempted")
        assertEquals(listOf("ruby"), manager().getInstalledToolchains(), "state should remain unchanged on failure")
        assertFalse(
            installMarkerFile("toolchain_ruby").exists(),
            "newly written cleanup marker was not removed when writeState failed",
        )
        assertFalse(
            releaseMarkerFile("toolchain_ruby").exists(),
            "pending release marker was not rolled back when writeState failed",
        )
        verify(exactly = 0) { packManager.removePack("toolchain_ruby") }
        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.STORAGE), reports)
    }

    @Test
    fun `cross-root upgrade reclaim restores shared usr bin symlink when deleted or overwritten`() {
        installedBy("com.example.sideloader")
        val java21Root = "usr/lib/jvm/java-21-openjdk"
        val java25Root = "usr/lib/jvm/java-25-openjdk"
        file("$java21Root/bin/java", "java-21")
        stateFile.writeText(
            """[{"name":"java","installRoot":"$java21Root","installId":"id-21",""" +
                """"binaries":["$java21Root/bin/java"],"symlinks":{"java":"$java21Root/bin/java"}}]""",
        )

        // 1. v2 unlinked usr/bin/java before Os.symlink failed, leaving no symlink on disk.
        file("$java25Root/bin/java", "java-25")
        installMarkerFile("toolchain_java").apply {
            parentFile?.mkdirs()
            writeText(
                """{"name":"java","installRoot":"$java25Root","installId":"id-25",""" +
                    """"binaries":["$java25Root/bin/java"],"symlinks":{"java":"$java25Root/bin/java"}}""",
            )
        }
        assertFalse(existsNoFollow("usr/bin/java"))

        reclaimInterrupted()

        assertFalse(existsNoFollow(java25Root))
        assertFalse(installMarkerFile("toolchain_java").exists())
        assertTrue(Files.isSymbolicLink(File(filesDir, "usr/bin/java").toPath()), "missing shared symlink not restored")
        assertEquals(
            File(filesDir, "$java21Root/bin/java").absolutePath,
            Os.readlink(File(filesDir, "usr/bin/java").absolutePath),
        )

        // 2. v2 overwrote usr/bin/java to point at v2 before failing; reclaim repoints it back to v1.
        file("$java25Root/bin/java", "java-25")
        File(filesDir, "usr/bin/java").delete()
        link("usr/bin/java", "$java25Root/bin/java")
        installMarkerFile("toolchain_java").writeText(
            """{"name":"java","installRoot":"$java25Root","installId":"id-25-b",""" +
                """"binaries":["$java25Root/bin/java"],"symlinks":{"java":"$java25Root/bin/java"}}""",
        )

        reclaimInterrupted()

        assertFalse(existsNoFollow(java25Root))
        assertFalse(installMarkerFile("toolchain_java").exists())
        assertEquals(
            File(filesDir, "$java21Root/bin/java").absolutePath,
            Os.readlink(File(filesDir, "usr/bin/java").absolutePath),
        )
    }

    @Test
    fun `cross-root upgrade reclaim restores shared libSymlinks even on partial reclaim`() {
        installedBy("com.example.sideloader")
        val java21Root = "usr/lib/jvm/java-21-openjdk"
        val java25Root = "usr/lib/jvm/java-25-openjdk"
        file("$java21Root/bin/java", "java-21")
        file("usr/lib/libjvm.so.1.21", "lib-21")
        stateFile.writeText(
            """[{"name":"java","installRoot":"$java21Root","installId":"id-21",""" +
                """"libs":["libjvm.so.1.21"],"libSymlinks":{"libjvm.so.1":"libjvm.so.1.21"}}]""",
        )

        // Force PARTIAL reclaim via an unremovable directory at a v2 binary path after v2's root and lib are removed.
        file("$java25Root/bin/java", "java-25")
        file("usr/lib/libjvm.so.1.25", "lib-25")
        file("usr/bin/java25-stuck/keep.txt", "keep")
        link("usr/lib/libjvm.so.1", "usr/lib/libjvm.so.1.25")
        installMarkerFile("toolchain_java").apply {
            parentFile?.mkdirs()
            writeText(
                """{"name":"java","installRoot":"$java25Root","installId":"id-25",""" +
                    """"binaries":["usr/bin/java25-stuck"],"libs":["libjvm.so.1.25"],""" +
                    """"libSymlinks":{"libjvm.so.1":"libjvm.so.1.25"}}""",
            )
        }

        reclaimInterrupted()

        assertFalse(existsNoFollow(java25Root), "v2 install root should have been removed during partial reclaim")
        assertTrue(installMarkerFile("toolchain_java").exists(), "partial reclaim must retain marker for stuck path")
        assertEquals(
            File(filesDir, "usr/lib/libjvm.so.1.21").absolutePath,
            Os.readlink(File(filesDir, "usr/lib/libjvm.so.1").absolutePath),
            "shared lib symlink was left dangling after partial cross-root reclaim",
        )

        // Also restore when v2 deleted usr/lib/libjvm.so.1 before failing.
        File(filesDir, "usr/lib/libjvm.so.1").delete()
        file("$java25Root/bin/java", "java-25")
        reclaimInterrupted()
        assertTrue(Files.isSymbolicLink(File(filesDir, "usr/lib/libjvm.so.1").toPath()))
        assertEquals(
            File(filesDir, "usr/lib/libjvm.so.1.21").absolutePath,
            Os.readlink(File(filesDir, "usr/lib/libjvm.so.1").absolutePath),
        )
    }

    @Test
    fun `overlapping parent or child installRoot preserves installed toolchain and keeps witness marker`() {
        installedBy("com.example.sideloader")

        // 1. Parent v2 (usr/opt/java) over child v1 (usr/opt/java/21) with an extra disjoint lib.
        file("usr/opt/java/21/bin/java", "java-21")
        file("usr/lib/libonly-parent.so", "extra")
        stateFile.writeText(
            """[{"name":"java","installRoot":"usr/opt/java/21","installId":"id-21","execBitsChecked":true,""" +
                """"symlinks":{"java":"usr/opt/java/21/bin/java"}}]""",
        )
        installMarkerFile("toolchain_java").apply {
            parentFile?.mkdirs()
            writeText(
                """{"name":"java","installRoot":"usr/opt/java","installId":"id-parent",""" +
                    """"libs":["libonly-parent.so"],"symlinks":{"java":"usr/opt/java/bin/java"}}""",
            )
        }

        reclaimInterrupted()

        assertEquals("java-21", File(filesDir, "usr/opt/java/21/bin/java").readText(), "v1 was deleted")
        assertFalse(existsNoFollow("usr/lib/libonly-parent.so"), "disjoint orphan lib was not reclaimed")
        assertTrue(installMarkerFile("toolchain_java").exists(), "witness marker dropped despite overlapping parent root")
        assertEquals(
            File(filesDir, "usr/opt/java/21/bin/java").absolutePath,
            Os.readlink(File(filesDir, "usr/bin/java").absolutePath),
        )
        assertFalse(
            JSONArray(stateFile.readText()).getJSONObject(0).getBoolean("execBitsChecked"),
            "execBitsChecked should be reset on overlapping parent root",
        )

        // 2. Child v2 (usr/opt/java/25) inside parent v1 (usr/opt/java).
        file("usr/opt/java/bin/java", "java-base")
        file("usr/opt/java/25/bin/java", "java-25")
        stateFile.writeText(
            """[{"name":"java","installRoot":"usr/opt/java","installId":"id-base","execBitsChecked":true}]""",
        )
        installMarkerFile("toolchain_java").writeText(
            """{"name":"java","installRoot":"usr/opt/java/25","installId":"id-25"}""",
        )

        reclaimInterrupted()

        assertEquals("java-base", File(filesDir, "usr/opt/java/bin/java").readText())
        assertTrue(installMarkerFile("toolchain_java").exists(), "witness marker dropped despite overlapping child root")
        val afterFirst = JSONArray(stateFile.readText()).getJSONObject(0)
        assertFalse(afterFirst.getBoolean("execBitsChecked"))

        // Second launch with the same installId does not reset execBitsChecked again.
        afterFirst.put("execBitsChecked", true)
        stateFile.writeText(JSONArray().put(afterFirst).toString())
        reclaimInterrupted()
        assertTrue(
            JSONArray(stateFile.readText()).getJSONObject(0).getBoolean("execBitsChecked"),
            "execBitsChecked should only reset once per installId",
        )

        // 3. Disjoint cross-root sharing a binary reclaims orphan root, clears marker, and resets execBitsChecked.
        file("usr/opt/java21/bin/java", "java-21")
        file("usr/opt/java25/bin/java", "java-25")
        file("usr/bin/jtool", "shared-tool")
        stateFile.writeText(
            """[{"name":"java","installRoot":"usr/opt/java21","installId":"id-21","execBitsChecked":true,""" +
                """"binaries":["usr/bin/jtool"]}]""",
        )
        installMarkerFile("toolchain_java").writeText(
            """{"name":"java","installRoot":"usr/opt/java25","installId":"id-25",""" +
                """"binaries":["usr/bin/jtool"]}""",
        )

        reclaimInterrupted()

        assertFalse(existsNoFollow("usr/opt/java25"), "disjoint v2 root was not reclaimed")
        assertFalse(installMarkerFile("toolchain_java").exists(), "settled disjoint marker was not cleared")
        assertTrue(File(filesDir, "usr/bin/jtool").exists(), "shared binary was deleted")
        assertFalse(
            JSONArray(stateFile.readText()).getJSONObject(0).getBoolean("execBitsChecked"),
            "shared binary overlap must reset execBitsChecked",
        )
    }

    @Test
    fun `install rejects invalid installRoot and libs entries and repairInstalledToolchainsSync stays confined`() {
        installedBy("com.example.sideloader")

        for (badRoot in listOf("home", "usr", "usr/bin", "usr/lib", "../outside")) {
            val pack = File(filesDir, "pack-bad-root-${badRoot.replace('/', '_').replace('.', 'd')}").apply {
                mkdirs()
                File(this, "toolchain_java.json").writeText(
                    JSONObject().put("name", "java").put("installRoot", badRoot).toString(),
                )
                File(this, "usr/opt/placeholder").apply {
                    parentFile?.mkdirs()
                    writeText("x")
                }
            }
            reports.clear()
            installFromDirectory(manager(), "toolchain_java", pack)
            assertEquals(
                listOf(AssetPackStatus.FAILED to ToolchainFailure.CORRUPT),
                reports,
                "installRoot '$badRoot' should be rejected as CORRUPT",
            )
            assertEquals(emptyList<String>(), manager().getInstalledToolchains())
        }

        // Redirected ancestor for installRoot is rejected as INTERNAL.
        File(filesDir, "home/outside-opt").mkdirs()
        link("usr/opt", "home/outside-opt")
        val redirectedRootPack = File(filesDir, "pack-redirect-root").apply {
            mkdirs()
            File(this, "toolchain_java.json").writeText("""{"name":"java","installRoot":"usr/opt/java"}""")
            File(this, "usr/opt/java/bin").mkdirs()
            File(this, "usr/opt/java/bin/java").writeText("java")
        }
        reports.clear()
        installFromDirectory(manager(), "toolchain_java", redirectedRootPack)
        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.INTERNAL), reports)
        assertFalse(existsNoFollow("home/outside-opt/java"), "install copied through redirected installRoot")
        File(filesDir, "usr/opt").delete()

        // Escaping libs entries are rejected as CORRUPT.
        for (badLib in listOf("../evil.so", "sub/evil.so", "..")) {
            val pack = File(filesDir, "pack-bad-lib-${badLib.replace('/', '_').replace('.', 'd')}").apply {
                mkdirs()
                File(this, "toolchain_java.json").writeText(
                    JSONObject()
                        .put("name", "java")
                        .put("installRoot", "usr/opt/java")
                        .put("libs", JSONArray().put(badLib))
                        .toString(),
                )
                File(this, "usr/opt/java/bin").mkdirs()
                File(this, "usr/opt/java/bin/java").writeText("java")
            }
            reports.clear()
            installFromDirectory(manager(), "toolchain_java", pack)
            assertEquals(
                listOf(AssetPackStatus.FAILED to ToolchainFailure.CORRUPT),
                reports,
                "lib '$badLib' should be rejected as CORRUPT",
            )
        }

        // Redirected usr/lib ancestor for libs is rejected as INTERNAL.
        File(filesDir, "home/outside-lib").mkdirs()
        link("usr/lib", "home/outside-lib")
        val redirectedLibPack = File(filesDir, "pack-redirect-lib").apply {
            mkdirs()
            File(this, "toolchain_java.json").writeText(
                """{"name":"java","installRoot":"usr/opt/java","libs":["libfoo.so"]}""",
            )
            File(this, "usr/lib").mkdirs()
            File(this, "usr/lib/libfoo.so").writeText("foo")
        }
        reports.clear()
        installFromDirectory(manager(), "toolchain_java", redirectedLibPack)
        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.INTERNAL), reports)
        assertFalse(existsNoFollow("home/outside-lib/libfoo.so"), "install wrote through redirected usr/lib")
        File(filesDir, "usr/lib").delete()

        // repairInstalledToolchainsSync refuses to walk or chmod outside managed usr/ roots or symlinked roots.
        val homeElf = elf("home/outside-root/bin/tool")
        val usrBinElf = elf("usr/bin/top-elf")
        val linkedTargetElf = elf("home/linked-target/bin/tool")
        link("usr/opt/linked-root", "home/linked-target")
        stateFile.writeText(
            """[
                {"name":"a","installRoot":"home/outside-root"},
                {"name":"b","installRoot":"usr/bin"},
                {"name":"c","installRoot":"usr/opt/linked-root"}
            ]""".trimIndent(),
        )

        repairSync()

        assertFalse(homeElf.canExecute(), "repair walked an installRoot outside usr/")
        assertFalse(usrBinElf.canExecute(), "repair walked a top-level shared usr/bin installRoot")
        assertFalse(linkedTargetElf.canExecute(), "repair followed a symlinked installRoot")
    }
}
