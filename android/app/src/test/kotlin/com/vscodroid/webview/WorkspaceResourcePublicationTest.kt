package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class WorkspaceResourcePublicationTest {
    @TempDir lateinit var files: File

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
}
