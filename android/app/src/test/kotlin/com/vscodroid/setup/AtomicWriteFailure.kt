package com.vscodroid.setup

import io.mockk.every
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

internal class AtomicWriteFailure : AutoCloseable {
    val attempts = AtomicInteger()
    val enabled = AtomicBoolean(true)
    override fun close() { enabled.set(false) }
}

/** Fail the real writer's temporary open after the fixture registers Files as a static mock. */
internal fun blockAtomicWritesTo(dest: File): AtomicWriteFailure {
    val parent = dest.absoluteFile.parentFile.toPath().normalize()
    val prefix = ".vscodroid-${Integer.toUnsignedString(dest.name.hashCode(), 16)}-"
    val failure = AtomicWriteFailure()
    every {
        Files.newOutputStream(match {
            failure.enabled.get() && it.toAbsolutePath().normalize().parent == parent &&
                it.fileName.toString().let { name -> name.startsWith(prefix) && name.endsWith(".tmp~") }
        }, *anyVararg())
    } answers {
        failure.attempts.incrementAndGet()
        throw IOException("No space left on device")
    }
    return failure
}
