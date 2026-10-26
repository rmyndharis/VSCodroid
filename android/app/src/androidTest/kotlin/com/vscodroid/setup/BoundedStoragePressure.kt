package com.vscodroid.setup

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Allocation is permitted only on an explicitly selected, separate small filesystem. */
internal fun createBoundedStoragePressure(
    enabled: Boolean,
    sameDeviceAsAppData: Boolean,
    volumeBytes: Long,
    availableBytes: () -> Long,
    write: (ByteArray) -> Unit,
    nanoTime: () -> Long = System::nanoTime,
): Boolean {
    val targetFree = 4L * 1024 * 1024
    val maxWritten = 64L * 1024 * 1024
    if (!enabled || sameDeviceAsAppData || volumeBytes !in 1..128L * 1024 * 1024) return false
    val initialFree = availableBytes()
    if (initialFree <= targetFree || initialFree - targetFree > maxWritten) return false
    val deadline = nanoTime() + TimeUnit.SECONDS.toNanos(10)
    val chunk = ByteArray(64 * 1024)
    var written = 0L
    while (availableBytes() > targetFree) {
        if (written + chunk.size > maxWritten || nanoTime() >= deadline) return false
        try {
            write(chunk)
            written += chunk.size
        } catch (_: IOException) {
            return availableBytes() <= targetFree
        }
    }
    return true
}
