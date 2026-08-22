package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which command names may be written into `toolchain-env.sh` as shell functions.
 *
 * `.bashrc` sources that file unconditionally, and one unusable name does not cost
 * one command: it costs the rest of the file. Measured by sourcing a file holding
 * `good() {...}`, `a=b() {...}`, `after() {...}`: `good` is defined and `after` is
 * gone. A toolchain manifest carrying a bad name would therefore take out every
 * wrapper written after it, in every new terminal.
 *
 * Every expectation below was checked against real bash: define the function,
 * then ask `declare -F` whether that name exists. That mattered: the first version
 * of this predicate applied the rule for shell *variables*
 * (`[A-Za-z_][A-Za-z0-9_]*`) and would have refused `grpc-tool`, `2to3` and
 * `foo.bar`, all of which bash defines without complaint. Over-refusing silently
 * drops a working wrapper, which is the failure this file exists to prevent, in
 * the other direction.
 */
class ShellFunctionNameTest {

    @Test
    fun `the names the shipped manifests contain are accepted`() {
        listOf("go", "gofmt", "java", "javac", "jarsigner", "ruby", "irb", "rdoc",
               "compile", "test2json", "_private", "a")
            .forEach { assertTrue(isShellFunctionName(it), "$it should be usable") }
    }

    @Test
    fun `shapes that look wrong but that bash accepts are allowed through`() {
        // Each verified against bash. Refusing these would drop a wrapper for a
        // command that would have worked, and an upstream package is free to ship
        // any of them.
        listOf("grpc-tool", "2to3", "foo.bar", "a+b", "a:b", "a@b", "a%b", "a^b",
               "a!b", "a,b", "a{b", "a}b", "a]b", "a*b", "a?b", "a#b", "a~b", "café")
            .forEach { assertTrue(isShellFunctionName(it), "bash defines $it fine") }
    }

    @Test
    fun `characters that make the definition a parse error are refused`() {
        // These are the ones that kill the rest of the sourced file.
        listOf("a(b", "a)b", "a<b", "a>b", "a=b", "a[b", "a\$b", "a\\b", "a\"b",
               "a'b", "a`b")
            .forEach { assertFalse(isShellFunctionName(it), "$it must not be written") }
    }

    @Test
    fun `separators that silently define another name are refused`() {
        // Worse than an error: bash splits the line, defines something under a
        // different name, and reports nothing. The wrapper is then absent under
        // the name the user types, with no diagnostic anywhere.
        listOf("a;b", "a|b", "a&b").forEach {
            assertFalse(isShellFunctionName(it), "$it would define the wrong name")
        }
    }

    @Test
    fun `whitespace in any form is refused`() {
        listOf("a b", "a\tb", "a\nb", " leading", "trailing ")
            .forEach { assertFalse(isShellFunctionName(it), "[$it] must not be written") }
    }

    @Test
    fun `an empty name is refused`() {
        assertFalse(isShellFunctionName(""))
    }
}
