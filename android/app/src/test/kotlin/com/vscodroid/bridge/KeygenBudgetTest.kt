package com.vscodroid.bridge

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The ssh-keygen timeout against the timeout of the only thing that calls it.
 *
 * Two budgets on one operation, written in different languages and different
 * files, with nothing holding them together. The Kotlin side waited sixty
 * seconds and said so because "the JavaScript side has no timeout of its own",
 * which the shipped relay contradicts: it rejects at five. So a slow keygen told
 * the user it had failed at five seconds and then held the bridge thread for
 * fifty-five more, and that thread is shared with clipboard, storage and
 * toolchain calls.
 *
 * Read off both sources rather than restated here, because a third copy of the
 * number is the defect this is about.
 *
 * The relay side is read as the SMALLEST deadline it declares. It declares more
 * than one now: the commands that walk the user's disk before they can answer
 * cannot be held to the deadline written for a relay hop. The keygen command is
 * not one of those, so the short one is what it gets, and taking the minimum
 * stays right if that ever changes.
 */
class KeygenBudgetTest {

    private val bridge = File("src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt")

    /**
     * Found by prefix rather than named with its version, because the version is in the
     * directory name and moves whenever the extension is edited. A pinned path made an
     * unrelated bump fail here with "not found", which reads as the check being broken
     * rather than as the path being stale, and the tempting repair is to delete the
     * check.
     */
    private val relay = File("src/main/assets/extensions")
        .listFiles { f -> f.isDirectory && f.name.startsWith("vscodroid.vscodroid-saf-bridge-") }
        ?.singleOrNull()
        ?.let { File(it, "extension.js") }
        ?: File("src/main/assets/extensions/vscodroid.vscodroid-saf-bridge/extension.js")

    private fun read(f: File): String {
        check(f.isFile) {
            "${f.absolutePath} not found; this test would otherwise pass by looking at nothing"
        }
        return f.readText()
    }

    @Test
    fun `the keygen timeout is shorter than the relay that waits on it`() {
        val kotlinSeconds = Regex("""KEYGEN_TIMEOUT_SECONDS = (\d+)L""")
            .find(read(bridge))?.groupValues?.get(1)?.toLong()
        // Every deadline the relay declares, and the smallest of them, because that
        // is the one this side has to beat. The relay used to have a single literal
        // in its `setTimeout`, which is what this read; it now names its deadlines so
        // the commands that walk the user's disk are not held to the one meant for a
        // relay hop, and a read of the literal found nothing at all and said so.
        // Taking the minimum keeps the answer conservative whichever deadline the
        // keygen command ends up under.
        val relayMillis = Regex("""const \w*TIMEOUT_MS = (\d+);""")
            .findAll(read(relay))
            .map { it.groupValues[1].toLong() }
            .minOrNull()

        assertTrue(kotlinSeconds != null, "KEYGEN_TIMEOUT_SECONDS was renamed or removed")
        assertTrue(relayMillis != null, "no timeout found in the bridge relay")
        assertTrue(
            kotlinSeconds!! * 1000 < relayMillis!!,
            "ssh-keygen is given ${kotlinSeconds}s while the relay gives up after " +
                "${relayMillis}ms. The user is told the key failed and the bridge " +
                "thread stays blocked for the difference, with every other bridge " +
                "call queued behind it.",
        )
    }
}
