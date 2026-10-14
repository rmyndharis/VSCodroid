package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What may leave the device in a backup, on the two generations that read
 * different files.
 *
 * Android 11 and below read `android:fullBackupContent`; Android 12 and above
 * read `android:dataExtractionRules`. With `minSdk` 30 both are live, and an
 * app whose rules disagree backs up one set on one release and another on the
 * next. The set that matters is what is NOT in them: the SSH key generated
 * without a passphrase and the server's connection token sit under `home/` and
 * `data/`, and a rule file that stops being an allowlist, or an attribute that
 * stops naming its file, sends them to the cloud with nothing failing and
 * nothing logged.
 *
 * Reads the XML, so it proves the two files agree and the manifest names them,
 * not that a device honours them.
 */
class BackupRulesParityTest {

    private val manifest = File("src/main/AndroidManifest.xml")
    private val legacy = File("src/main/res/xml/backup_rules.xml")
    private val modern = File("src/main/res/xml/data_extraction_rules.xml")

    private fun root(file: File): Element {
        check(file.isFile) { "${file.path} not found; this test would otherwise pass by reading nothing" }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    /** `domain:path` for every `<include>` under [parent], and the number of `<exclude>` there. */
    private fun rules(parent: Element): Pair<Set<String>, Int> {
        val includes = parent.getElementsByTagName("include")
        val set = (0 until includes.length).map { includes.item(it) as Element }
            .map { "${it.getAttribute("domain")}:${it.getAttribute("path")}" }
            .toSet()
        return set to parent.getElementsByTagName("exclude").length
    }

    private fun section(name: String): Element {
        val nodes = root(modern).getElementsByTagName(name)
        assertEquals(1, nodes.length, "data_extraction_rules.xml has no single <$name> section")
        return nodes.item(0) as Element
    }

    @Test
    fun `both generations back up the same allowlist and nothing is excluded by name`() {
        val old = rules(root(legacy))
        val cloud = rules(section("cloud-backup"))
        val transfer = rules(section("device-transfer"))

        // The control: a parse that matched nothing would make three empty sets equal.
        assertTrue(old.first.isNotEmpty()) { "backup_rules.xml names no <include>, so it backs up everything" }
        assertEquals(cloud, old) {
            "backup_rules.xml (Android 11 and below) and the <cloud-backup> section of " +
                "data_extraction_rules.xml (Android 12 and above) no longer agree"
        }
        assertEquals(cloud, transfer) {
            "the <cloud-backup> and <device-transfer> sections no longer agree"
        }
        assertEquals(0, old.second) {
            "an <exclude> is back: these files are allowlists, and a denylist protects only what " +
                "somebody remembered to list"
        }
    }

    @Test
    fun `the application names both files, so neither release falls back to everything`() {
        val application = root(manifest).getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/backup_rules", application.getAttribute("android:fullBackupContent")) {
            "Android 11 reads android:fullBackupContent, and without it backs up the whole data directory"
        }
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules")) {
            "Android 12 and above read android:dataExtractionRules, and without it back up the whole data directory"
        }
    }
}
