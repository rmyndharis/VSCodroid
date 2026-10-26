package com.vscodroid.webview

/** The complete resource-root set published by the current workbench document. */
internal class WorkspaceResourcePublication(private val generation: () -> Long) {
    @Volatile
    private var value: Pair<Long, List<String>>? = null

    @Synchronized
    fun publish(pageGeneration: Long, roots: List<String>): Boolean {
        if (pageGeneration != generation()) return false
        value = pageGeneration to roots.toList()
        return true
    }

    fun roots(): List<String>? {
        val published = value ?: return null
        return published.second.takeIf { published.first == generation() }
    }
}
