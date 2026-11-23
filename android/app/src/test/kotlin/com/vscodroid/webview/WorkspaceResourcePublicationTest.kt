package com.vscodroid.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class WorkspaceResourcePublicationTest {
    @TempDir lateinit var files: File

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun publishedMembersReplaceThePriorRootAndAnEmptySetClosesIt() {
        var generation = 1L
        val publication = WorkspaceResourcePublication { generation }
        val config = File(files, "config").apply { mkdirs() }
        val a = File(files, "external-a").apply { mkdirs() }
        val b = File(files, "external-b").apply { mkdirs() }
        val aImage = File(a, "image.png").apply { writeText("A") }
        val bImage = File(b, "image.png").apply { writeText("B") }
        val configImage = File(config, "image.png").apply { writeText("CONFIG") }
        fun roots() = workspaceResourceRootsInForce(emptyList(), emptyList(), a.path, publication.roots())
        assertTrue(publication.publish(generation, listOf(config.path, a.path)))
        assertTrue(resourceOutcome(aImage.path, roots()) is ResourceOutcome.Serve)
        assertTrue(resourceOutcome(configImage.path, roots()) is ResourceOutcome.Serve)
        assertEquals(ResourceOutcome.Refused, resourceOutcome(bImage.path, roots()))
        assertTrue(publication.publish(generation, listOf(b.path)))
        assertEquals(ResourceOutcome.Refused, resourceOutcome(aImage.path, roots()))
        assertEquals(ResourceOutcome.Refused, resourceOutcome(configImage.path, roots()))
        assertTrue(resourceOutcome(bImage.path, roots()) is ResourceOutcome.Serve)
        assertTrue(publication.publish(generation, emptyList()))
        assertEquals(emptyList<String>(), roots())
        assertEquals(ResourceOutcome.Refused, resourceOutcome(aImage.path, roots()))
        generation++
        assertFalse(publication.publish(generation - 1, listOf(a.path)))
        assertNull(publication.roots())
    }

    @Test
    fun memberRootsRetainCanonicalAndSensitiveLocationRestrictions() {
        val member = File(files, "member").apply { mkdirs() }
        val home = File(files, "home").apply { mkdirs() }
        val ssh = File(home, ".ssh").apply { mkdirs() }
        val secret = File(ssh, "key").apply { writeText("DUMMY") }
        Files.createSymbolicLink(File(member, "outside").toPath(), secret.toPath())
        val roots = workspaceResourceRootsInForce(emptyList(), listOf(ssh.canonicalPath), null,
            listOf(member.path, home.path, ssh.path))
        assertEquals(listOf(member.canonicalPath), roots)
        assertEquals(ResourceOutcome.Refused, resourceOutcome(secret.path, roots))
        assertEquals(ResourceOutcome.Refused, resourceOutcome(File(member, "outside").path, roots))
    }

    @Test
    fun appPrivateStoresAndCredentialDirectoriesAreNeverPublished() {
        val data = File(files, "data").apply { mkdirs() }
        val filesDir = File(data, "files").apply { mkdirs() }
        val noBackup = File(data, "no_backup").apply { mkdirs() }
        val cache = File(data, "cache").apply { mkdirs() }
        val context = mockk<Context>(relaxed = true) {
            every { this@mockk.filesDir } returns filesDir
            every { noBackupFilesDir } returns noBackup
            every { dataDir } returns data
            every { cacheDir } returns cache
        }
        val home = File(filesDir, "home")
        val sensitive = sensitiveLocations(context)
        val refused = listOf(
            File(home, ".ssh"), File(home, ".vscodroid/data/token"), noBackup, File(noBackup, "secret-storage.key"),
            File(data, "app_webview_vscodroid"), File(data, "shared_prefs"), File(data, "databases"),
            File(cache, "webview_vscodroid"), File(home, ".aws"), File(home, ".aws/credentials"),
            File(home, ".gnupg"), File(home, ".config/gh"), File(home, ".docker"), File(home, ".kube"),
            File(home, ".git-credentials"), File(home, ".netrc"), File(home, ".npmrc"),
            // A folder that holds one of them is as readable as the one inside it.
            File(home, ".config"), home, data,
        )
        for (path in refused) {
            assertNull(workspaceRootOrNull(path.path, sensitive), "${path.path} was published")
        }
        val accepted = listOf(
            File(home, "projects/app"), File(home, ".aws-notes"), File(home, ".config-backup"),
            File(filesDir, "saf-mirrors/0123456789ab"), File(files, "external/projects"),
        )
        for (path in accepted) {
            assertEquals(path.canonicalPath, workspaceRootOrNull(path.path, sensitive), "${path.path} was refused")
        }
    }

    @Test
    fun publishedMembersExcludeUnrelatedProjectsAndClosedSafMirrorsWhileKeepingExtensions() {
        val data = File(files, "narrow-data").apply { mkdirs() }
        val filesDir = File(data, "files").apply { mkdirs() }
        val noBackup = File(data, "no_backup").apply { mkdirs() }
        val cache = File(data, "cache").apply { mkdirs() }
        val context = mockk<Context>(relaxed = true) {
            every { this@mockk.filesDir } returns filesDir
            every { noBackupFilesDir } returns noBackup
            every { dataDir } returns data
            every { cacheDir } returns cache
            every { getExternalFilesDir(null) } returns null
        }

        val serverAsset = File(filesDir, "server/extensions/md/media/m.css").apply { parentFile?.mkdirs(); writeText("css") }
        val extAsset = File(filesDir, "home/.vscodroid/extensions/pub.ext/logo.png").apply { parentFile?.mkdirs(); writeText("png") }
        val projectA = File(filesDir, "projects/app-a").apply { mkdirs() }
        val projectAFile = File(projectA, "diagram.png").apply { writeText("a") }
        val projectBFile = File(filesDir, "projects/app-b/secret.txt").apply { parentFile?.mkdirs(); writeText("b") }
        val closedMirrorFile = File(filesDir, "saf-mirrors/deadbeef1234/private.txt").apply { parentFile?.mkdirs(); writeText("m") }

        val logged = mutableListOf<String>()
        mockkObject(Logger)
        every { Logger.d(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        mockkConstructor(WebResourceResponse::class)
        mockResourceFileOpening()

        var members: List<String>? = listOf(projectA.path)
        val client = VSCodroidWebViewClient(
            allowedPort = { 41234 },
            resourceRules = ResourceRules.of(context),
            openFolder = { projectA.path },
            connectionToken = { null },
            onCrash = {},
            onPageLoaded = {},
            onRetryServer = {},
            workspaceMembers = { members },
        )
        val view = mockk<WebView>(relaxed = true)
        fun ask(file: File): String {
            logged.clear()
            val uri = mockk<Uri>(relaxed = true)
            every { uri.host } returns "file+.vscode-resource.vscode-cdn.net"
            every { uri.path } returns file.path
            every { uri.encodedQuery } returns null
            every { uri.query } returns null
            val req = mockk<WebResourceRequest>(relaxed = true)
            every { req.url } returns uri
            every { req.requestHeaders } returns mapOf("Referer" to "https://0f7c2b1a.vscode-cdn.net/")
            client.shouldInterceptRequest(view, req)
            return logged.joinToString("\n")
        }

        // Active member and static app/extension roots are served.
        assertTrue(ask(serverAsset).contains("Resource served"))
        assertTrue(ask(extAsset).contains("Resource served"))
        assertTrue(ask(projectAFile).contains("Resource served"))
        // Unrelated project and closed SAF mirror must be refused once members are published.
        assertTrue(ask(projectBFile).contains("outside the published roots"), "unrelated project was served")
        assertTrue(ask(closedMirrorFile).contains("outside the published roots"), "closed SAF mirror was served")

        // Empty window refuses all project and SAF mirror paths while keeping server and extension assets.
        members = emptyList()
        assertTrue(ask(serverAsset).contains("Resource served"))
        assertTrue(ask(extAsset).contains("Resource served"))
        assertTrue(ask(projectAFile).contains("outside the published roots"), "project served in empty window")
        assertTrue(ask(closedMirrorFile).contains("outside the published roots"), "mirror served in empty window")
    }
}
