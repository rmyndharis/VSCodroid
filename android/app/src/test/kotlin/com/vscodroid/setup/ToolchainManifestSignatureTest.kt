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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal loopback HTTP responder for manifest signature end-to-end tests.
 */
private class SignatureLoopbackServer {
    private val socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    @Volatile
    var routes: Map<String, ByteArray> = emptyMap()

    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val thread = Thread {
        while (!socket.isClosed) {
            try {
                socket.accept().use { serve(it) }
            } catch (_: Exception) {
                if (socket.isClosed) return@Thread
            }
        }
    }.apply { isDaemon = true; name = "sig-loopback-fixture" }

    fun start() = thread.start()

    fun stop() = socket.close()

    private fun serve(conn: Socket) {
        conn.soTimeout = 5_000
        val reader = conn.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val path = requestLine.split(" ").getOrNull(1) ?: "/"
        requested.add(path)

        val out = conn.getOutputStream()
        val body = routes[path]
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
        }
        out.flush()
        conn.shutdownOutput()
    }
}

class ToolchainManifestSignatureTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var packManager: AssetPackManager
    private lateinit var server: SignatureLoopbackServer

    private val events: MutableList<Triple<String, Int, Int>> = Collections.synchronizedList(mutableListOf())
    private val reasons: MutableList<ToolchainFailure> = Collections.synchronizedList(mutableListOf())
    private val settled = CountDownLatch(1)

    private val zipPath = "/download/toolchain_test.zip"
    private val manifestPath = "/download/toolchains.sha256"
    private val sigPath = "/download/toolchains.sha256.sig"

    private var savedPublicKeySpki: ByteArray? = null
    private var savedRequireSigned: Boolean = false

    private val zipBytes: ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("toolchain_test.json"))
            zip.write("""{"name":"test","installRoot":"usr/opt/test"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("usr/opt/test/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("usr/opt/test/payload.bin"))
            zip.write("signed-payload".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private val zipDigest: String = MessageDigest.getInstance("SHA-256")
        .digest(zipBytes)
        .joinToString("") { "%02x".format(it) }

    private fun generateP256KeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun signBytes(keyPair: KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(data)
            sign()
        }

    @BeforeEach
    fun setUp() {
        savedPublicKeySpki = ToolchainManifestVerifier.manifestPublicKeySpki
        savedRequireSigned = ToolchainManifestVerifier.requireSignedManifest

        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        packManager = mockk(relaxed = true)
        every { packManager.removePack(any()) } answers { completedPackRemoval() }
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

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

        server = SignatureLoopbackServer()
        server.start()
        val info = ToolchainRegistry.ToolchainInfo(
            packName = "toolchain_test",
            displayName = "Test",
            shortLabel = "Test",
            descriptionRes = 0,
            estimatedSize = 1_000_000L,
            downloadSize = zipBytes.size.toLong(),
            downloadUrl = "http://127.0.0.1:${server.port}$zipPath",
        )
        mockkObject(ToolchainRegistry)
        every { ToolchainRegistry.available } returns listOf(info)
        every { ToolchainRegistry.find("toolchain_test") } returns info
        every { ToolchainRegistry.find("test") } returns info
    }

    @AfterEach
    fun tearDown() {
        ToolchainManifestVerifier.manifestPublicKeySpki = savedPublicKeySpki
        ToolchainManifestVerifier.requireSignedManifest = savedRequireSigned
        server.stop()
        unmockkAll()
    }

    private fun runInstall(): ToolchainManager {
        val manager = ToolchainManager(context)
        manager.onStateChange = { pack, status, percent, why, _ ->
            events.add(Triple(pack, status, percent))
            if (why != null) reasons.add(why)
            if (status == AssetPackStatus.COMPLETED || status == AssetPackStatus.FAILED) {
                settled.countDown()
            }
        }
        manager.install("toolchain_test")
        assertTrue(settled.await(30, TimeUnit.SECONDS), "install did not settle: $events")
        awaitLaunchPass(manager)
        return manager
    }

    // -- Unit tests for ToolchainManifestVerifier and URL derivation --

    @Test
    fun `manifestSignatureUrlFor derives toolchains sha256 sig beside the zip`() {
        assertEquals(
            "https://github.com/rmyndharis/VSCodroid/releases/download/v1.10.0/toolchains.sha256.sig",
            manifestSignatureUrlFor(
                "https://github.com/rmyndharis/VSCodroid/releases/download/v1.10.0/toolchain_ruby.zip"
            ),
        )
    }

    @Test
    fun `valid ECDSA P-256 signature verifies raw manifest bytes`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val sigBytes = signBytes(keyPair, manifestBytes)

        assertTrue(ToolchainManifestVerifier.verify(manifestBytes, sigBytes, keyPair.public.encoded))
    }

    @Test
    fun `line ending mutation fails raw byte verification`() {
        val keyPair = generateP256KeyPair()
        val lfBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val crlfBytes = "$zipDigest  toolchain_test.zip\r\n".toByteArray(Charsets.UTF_8)
        val sigBytes = signBytes(keyPair, lfBytes)

        assertFalse(ToolchainManifestVerifier.verify(crlfBytes, sigBytes, keyPair.public.encoded))
    }

    @Test
    fun `tampered manifest byte fails closed`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val sigBytes = signBytes(keyPair, manifestBytes)
        val tampered = manifestBytes.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }

        assertFalse(ToolchainManifestVerifier.verify(tampered, sigBytes, keyPair.public.encoded))
    }

    @Test
    fun `tampered signature byte fails closed`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val sigBytes = signBytes(keyPair, manifestBytes)
        val tampered = sigBytes.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }

        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, tampered, keyPair.public.encoded))
    }

    @Test
    fun `signature from a different P-256 key fails closed`() {
        val signingKey = generateP256KeyPair()
        val verifyingKey = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val sigBytes = signBytes(signingKey, manifestBytes)

        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, sigBytes, verifyingKey.public.encoded))
    }

    @Test
    fun `empty, oversized, and malformed inputs fail closed without throwing`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val validSig = signBytes(keyPair, manifestBytes)

        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, ByteArray(0), keyPair.public.encoded))
        assertFalse(
            ToolchainManifestVerifier.verify(
                manifestBytes,
                ByteArray(ToolchainManifestVerifier.MAX_SIGNATURE_BYTES + 1) { 0x30 },
                keyPair.public.encoded,
            ),
        )
        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, byteArrayOf(1, 2, 3, 4), keyPair.public.encoded))
        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, validSig, ByteArray(0)))
        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, validSig, byteArrayOf(0x30, 0x01, 0x00)))
    }

    @Test
    fun `non-P-256 curve key is rejected`() {
        val p384 = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val sigBytes = signBytes(p384, manifestBytes)

        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, sigBytes, p384.public.encoded))
    }

    @Test
    fun `decodePublicKeySpki handles valid Base64, blank input, and invalid Base64`() {
        val keyPair = generateP256KeyPair()
        val spkiBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val wrapped = spkiBase64.chunked(32).joinToString("\n")

        assertNull(ToolchainManifestVerifier.decodePublicKeySpki(""))
        assertNull(ToolchainManifestVerifier.decodePublicKeySpki("   \n\t  "))
        assertNull(ToolchainManifestVerifier.decodePublicKeySpki("%%%not-base64%%%"))

        val decoded = ToolchainManifestVerifier.decodePublicKeySpki(wrapped)
        assertNotNull(decoded)
        assertTrue(keyPair.public.encoded.contentEquals(decoded))
    }

    @Test
    fun `real openssl dgst sha256 sign signature verifies with ToolchainManifestVerifier`() {
        val work = File(filesDir, "openssl-interop").apply { mkdirs() }
        val privPem = File(work, "priv.pem")
        val pubDer = File(work, "pub.der")
        val manifestFile = File(work, "toolchains.sha256")
        val sigFile = File(work, "toolchains.sha256.sig")
        manifestFile.writeBytes("$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8))

        fun exec(vararg cmd: String) {
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            assertEquals(0, proc.waitFor(), "command failed: ${cmd.joinToString(" ")}\n$output")
        }

        exec("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", privPem.absolutePath)
        exec("openssl", "pkey", "-in", privPem.absolutePath, "-pubout", "-outform", "DER", "-out", pubDer.absolutePath)
        exec(
            "openssl", "dgst", "-sha256",
            "-sign", privPem.absolutePath,
            "-out", sigFile.absolutePath,
            manifestFile.absolutePath,
        )

        val spkiBase64 = Base64.getEncoder().encodeToString(pubDer.readBytes())
        val decodedSpki = ToolchainManifestVerifier.decodePublicKeySpki(spkiBase64)!!
        val manifestBytes = manifestFile.readBytes()
        val sigBytes = sigFile.readBytes()

        assertTrue(ToolchainManifestVerifier.verify(manifestBytes, sigBytes, decodedSpki))
        val tampered = manifestBytes.copyOf().also { it[it.lastIndex] = 'X'.code.toByte() }
        assertFalse(ToolchainManifestVerifier.verify(tampered, sigBytes, decodedSpki))
    }

    // -- End-to-end loopback HTTP integration tests --

    @Test
    fun `valid signed manifest installs cleanly over loopback HTTP`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val sigBytes = signBytes(keyPair, manifestBytes)

        ToolchainManifestVerifier.manifestPublicKeySpki = keyPair.public.encoded
        ToolchainManifestVerifier.requireSignedManifest = true
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to manifestBytes,
            sigPath to sigBytes,
        )

        runInstall()

        assertEquals(AssetPackStatus.COMPLETED, events.last().second, "expected COMPLETED, got $events; $reasons")
        assertTrue(reasons.isEmpty(), "unexpected failures: $reasons")
        assertTrue(server.requested.contains(sigPath), "signature endpoint was never requested: ${server.requested}")
        assertTrue(
            server.requested.indexOf(sigPath) < server.requested.indexOf(zipPath),
            "signature must be verified before downloading the ZIP: ${server.requested}",
        )
    }

    @Test
    fun `missing signature returns 404 and fails closed with DIGEST before downloading ZIP`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)

        ToolchainManifestVerifier.manifestPublicKeySpki = keyPair.public.encoded
        ToolchainManifestVerifier.requireSignedManifest = true
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to manifestBytes,
        )

        runInstall()

        assertEquals(AssetPackStatus.FAILED, events.last().second, "expected FAILED, got $events")
        assertEquals(listOf(ToolchainFailure.DIGEST), reasons)
        assertFalse(server.requested.contains(zipPath), "ZIP must not be downloaded when signature is missing")
    }

    @Test
    fun `tampered manifest byte fails closed with DIGEST before downloading ZIP`() {
        val keyPair = generateP256KeyPair()
        val originalManifest = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val sigBytes = signBytes(keyPair, originalManifest)
        val tamperedManifest = "$zipDigest  toolchain_test.zip \n".toByteArray(Charsets.UTF_8)

        ToolchainManifestVerifier.manifestPublicKeySpki = keyPair.public.encoded
        ToolchainManifestVerifier.requireSignedManifest = true
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to tamperedManifest,
            sigPath to sigBytes,
        )

        runInstall()

        assertEquals(AssetPackStatus.FAILED, events.last().second)
        assertEquals(listOf(ToolchainFailure.DIGEST), reasons)
        assertFalse(server.requested.contains(zipPath))
    }

    @Test
    fun `tampered signature fails closed with DIGEST before downloading ZIP`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val badSig = signBytes(keyPair, manifestBytes).also {
            it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte()
        }

        ToolchainManifestVerifier.manifestPublicKeySpki = keyPair.public.encoded
        ToolchainManifestVerifier.requireSignedManifest = true
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to manifestBytes,
            sigPath to badSig,
        )

        runInstall()

        assertEquals(AssetPackStatus.FAILED, events.last().second)
        assertEquals(listOf(ToolchainFailure.DIGEST), reasons)
        assertFalse(server.requested.contains(zipPath))
    }

    @Test
    fun `truncated signature fails closed with DIGEST before downloading ZIP`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val fullSig = signBytes(keyPair, manifestBytes)
        val truncatedSig = fullSig.copyOf(fullSig.size / 2)

        ToolchainManifestVerifier.manifestPublicKeySpki = keyPair.public.encoded
        ToolchainManifestVerifier.requireSignedManifest = true
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to manifestBytes,
            sigPath to truncatedSig,
        )

        runInstall()

        assertEquals(AssetPackStatus.FAILED, events.last().second)
        assertEquals(listOf(ToolchainFailure.DIGEST), reasons)
        assertFalse(server.requested.contains(zipPath))
    }

    @Test
    fun `oversized signature fails closed with DIGEST before downloading ZIP`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val oversizedSig = ByteArray(ToolchainManifestVerifier.MAX_SIGNATURE_BYTES + 32) { 0x30 }

        ToolchainManifestVerifier.manifestPublicKeySpki = keyPair.public.encoded
        ToolchainManifestVerifier.requireSignedManifest = true
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to manifestBytes,
            sigPath to oversizedSig,
        )

        runInstall()

        assertEquals(AssetPackStatus.FAILED, events.last().second)
        assertEquals(listOf(ToolchainFailure.DIGEST), reasons)
        assertFalse(server.requested.contains(zipPath))
    }

    @Test
    fun `signature from wrong key fails closed with DIGEST before downloading ZIP`() {
        val expectedKey = generateP256KeyPair()
        val attackerKey = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val attackerSig = signBytes(attackerKey, manifestBytes)

        ToolchainManifestVerifier.manifestPublicKeySpki = expectedKey.public.encoded
        ToolchainManifestVerifier.requireSignedManifest = true
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to manifestBytes,
            sigPath to attackerSig,
        )

        runInstall()

        assertEquals(AssetPackStatus.FAILED, events.last().second)
        assertEquals(listOf(ToolchainFailure.DIGEST), reasons)
        assertFalse(server.requested.contains(zipPath))
    }

    @Test
    fun `requireSignedManifest without configured key fails closed with DIGEST`() {
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)

        ToolchainManifestVerifier.manifestPublicKeySpki = null
        ToolchainManifestVerifier.requireSignedManifest = true
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to manifestBytes,
        )

        runInstall()

        assertEquals(AssetPackStatus.FAILED, events.last().second)
        assertEquals(listOf(ToolchainFailure.DIGEST), reasons)
        assertFalse(server.requested.contains(zipPath))
    }

    @Test
    fun `unprovisioned build falls back to SHA-256 verification when no key is configured`() {
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)

        ToolchainManifestVerifier.manifestPublicKeySpki = null
        ToolchainManifestVerifier.requireSignedManifest = false
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to manifestBytes,
        )

        runInstall()

        assertEquals(AssetPackStatus.COMPLETED, events.last().second, "expected COMPLETED, got $events; $reasons")
        assertFalse(server.requested.contains(sigPath), "unprovisioned build should not fetch .sig")
    }

    @Test
    fun `trailing garbage bytes appended to a valid DER signature are rejected`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val validSig = signBytes(keyPair, manifestBytes)
        val paddedSingle = validSig + byteArrayOf(0x00)
        val paddedMulti = validSig + byteArrayOf(0x01, 0x02, 0x03)

        assertTrue(ToolchainManifestVerifier.verify(manifestBytes, validSig, keyPair.public.encoded))
        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, paddedSingle, keyPair.public.encoded))
        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, paddedMulti, keyPair.public.encoded))
    }

    @Test
    fun `RSA-2048 public key SPKI is rejected`() {
        val rsaKey = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val rsaSig = Signature.getInstance("SHA256withRSA").run {
            initSign(rsaKey.private)
            update(manifestBytes)
            sign()
        }
        val ecKey = generateP256KeyPair()
        val ecSig = signBytes(ecKey, manifestBytes)

        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, rsaSig, rsaKey.public.encoded))
        assertFalse(ToolchainManifestVerifier.verify(manifestBytes, ecSig, rsaKey.public.encoded))
    }

    @Test
    fun `oversized manifest fails closed with DIGEST before downloading ZIP`() {
        val keyPair = generateP256KeyPair()
        val header = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)
        val oversizedManifest = ByteArray(ToolchainManifestVerifier.MAX_MANIFEST_BYTES + 1) { '#'.code.toByte() }
        header.copyInto(oversizedManifest, destinationOffset = 0)
        val sigBytes = signBytes(keyPair, oversizedManifest)

        ToolchainManifestVerifier.manifestPublicKeySpki = keyPair.public.encoded
        ToolchainManifestVerifier.requireSignedManifest = true
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to oversizedManifest,
            sigPath to sigBytes,
        )

        runInstall()

        assertEquals(AssetPackStatus.FAILED, events.last().second)
        assertEquals(listOf(ToolchainFailure.DIGEST), reasons)
        assertFalse(server.requested.contains(zipPath), "ZIP must not be downloaded when manifest exceeds limit")
    }

    @Test
    fun `empty 0-byte signature HTTP 200 fails closed with DIGEST before downloading ZIP`() {
        val keyPair = generateP256KeyPair()
        val manifestBytes = "$zipDigest  toolchain_test.zip\n".toByteArray(Charsets.UTF_8)

        ToolchainManifestVerifier.manifestPublicKeySpki = keyPair.public.encoded
        ToolchainManifestVerifier.requireSignedManifest = true
        server.routes = mapOf(
            zipPath to zipBytes,
            manifestPath to manifestBytes,
            sigPath to ByteArray(0),
        )

        runInstall()

        assertEquals(AssetPackStatus.FAILED, events.last().second)
        assertEquals(listOf(ToolchainFailure.DIGEST), reasons)
        assertFalse(server.requested.contains(zipPath), "ZIP must not be downloaded when .sig is 0 bytes")
    }

    @Test
    fun `embedded release public key is configured as valid uncompressed NIST P-256 SPKI`() {
        assertTrue(ToolchainManifestVerifier.hasEmbeddedPublicKey)
        assertTrue(savedRequireSigned, "embedded key should default requireSignedManifest to true")
        val spki = savedPublicKeySpki
        assertNotNull(spki, "embedded SPKI Base64 failed to decode")
        val decoded = spki!!
        val expectedHeader = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(),
            0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d,
            0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
        )
        assertEquals(91, decoded.size, "uncompressed P-256 SPKI must be 91 bytes")
        assertTrue(
            decoded.copyOfRange(0, expectedHeader.size).contentEquals(expectedHeader),
            "embedded key SPKI header must match uncompressed NIST P-256",
        )
        val parsed = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decoded))
        assertTrue(parsed is ECPublicKey)
    }
}
