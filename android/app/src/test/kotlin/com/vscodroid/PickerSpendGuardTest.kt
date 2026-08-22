package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the first-run picker is allowed to spend when Continue is tapped.
 *
 * The cards refuse to tick a toolchain they have been told is installed, and
 * that is not enough on its own. The set behind the refusal is a reading of a
 * moment, and two things get past it: replacing the set unticks nothing, so a
 * pack ticked while it was genuinely absent keeps its tick once it is installed
 * from the Toolchains screen; and the delivered-pack reconcile writes the record
 * from its own thread while this screen waits for a person, with no lifecycle
 * callback to hang a re-read on.
 *
 * Nothing downstream catches it. `downloadNext` installs what it is handed and
 * `ToolchainManager.install` has no already-installed branch, so a tick that
 * gets through spends the download and the copy again, 55 MB and 155 MB for
 * Java 17, or meets the in-flight claim and paints a red "Failed" row for an
 * install that is going perfectly well.
 *
 * [notYetInstalled] is the last check before that spend, and these pin it.
 */
class PickerSpendGuardTest {

    /**
     * NEGATIVE CONTROL: make [notYetInstalled] return `selected.toList()` and
     * this goes red. That is precisely the line the Continue handler carried.
     */
    @Test
    fun `a toolchain already on disk is not downloaded again`() {
        assertEquals(
            listOf("toolchain_ruby"),
            notYetInstalled(setOf("toolchain_java", "toolchain_ruby"), listOf("java")),
            "the picker started a fresh install for a toolchain the record already " +
                "calls installed",
        )
    }

    /**
     * NEGATIVE CONTROL: drop the `ToolchainRegistry.find` from the installed side
     * of [notYetInstalled], so the record's names are compared as they are
     * written, and this goes red.
     */
    @Test
    fun `the two halves spell a toolchain differently and still match`() {
        // The install record names a toolchain the short way, the cards name it
        // the pack way, and both spellings reach here.
        assertTrue(
            notYetInstalled(setOf("toolchain_java"), listOf("java")).isEmpty(),
            "the short name in the install record did not match the pack name on the " +
                "card, so an installed toolchain reads as absent",
        )
        assertTrue(
            notYetInstalled(setOf("toolchain_java"), listOf("toolchain_java")).isEmpty(),
            "the pack spelling reaches here too, from a card that reported COMPLETED",
        )
    }

    @Test
    fun `nothing installed leaves the choice exactly as it was made`() {
        assertEquals(
            listOf("toolchain_java", "toolchain_ruby"),
            notYetInstalled(listOf("toolchain_java", "toolchain_ruby"), emptyList()),
            "the guard dropped a toolchain that is not installed, which costs the user " +
                "the one thing this screen exists to offer",
        )
    }

    /**
     * NEGATIVE CONTROL: make the lookup on the installed side assert instead,
     * `ToolchainRegistry.find(it)!!.packName`, and this goes red on the name the
     * registry no longer knows.
     */
    @Test
    fun `a name the registry does not know decides nothing`() {
        // Go was withdrawn from the registry and its name is still in the install
        // record on a device that had it. It has no card, so it can neither be
        // ticked nor filter anything, and it must not remove a live pack either.
        assertEquals(
            listOf("toolchain_java"),
            notYetInstalled(setOf("toolchain_java"), listOf("go", "")),
            "a name outside the registry changed what gets downloaded",
        )
    }
}
