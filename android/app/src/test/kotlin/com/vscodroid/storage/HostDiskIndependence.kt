package com.vscodroid.storage

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File

/**
 * Keeps every test in this suite from reading the free space of the disk it runs on.
 *
 * The sync engine refuses to fetch a document when less than
 * [SafSyncEngine.OPEN_SPACE_FLOOR_BYTES] is free where the mirror lives, and a test's mirror is
 * a JUnit temp directory on the developer's own volume. With that volume nearly full the fetch
 * is refused without a word, the document never reaches the mirror, and a save or delete for
 * it is turned away as unread: the cases that open a folder and then act on a file fail with
 * missing files and calls that never happened. Measured on a 100 MB volume, 22 of the 52 cases
 * in SafUnfetchedDocumentTest failed that way, and the same cases failed once in a full run
 * when the volume ran low for a few minutes.
 *
 * Registered through `META-INF/services` and switched on by
 * `junit.jupiter.extensions.autodetection.enabled` in `junit-platform.properties`, the way
 * mockk's own cleanup is, so no class has to remember it. A case about the floor itself
 * assigns `usableSpaceOf` on its engine, which wins over this default.
 */
class HostDiskIndependence : BeforeAllCallback {

    override fun beforeAll(context: ExtensionContext) {
        SafSyncEngine.defaultUsableSpaceOf = { Long.MAX_VALUE }
    }

    internal companion object {
        /** The default the app ships, read before the first class replaces it. */
        val shipped: (File) -> Long = SafSyncEngine.defaultUsableSpaceOf
    }
}
