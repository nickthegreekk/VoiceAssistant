package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The models-screen selection gate.
 *
 * `modelsServerUrl` is `rememberSaveable`, so a rotation restores it, while the collected Ollama
 * list it is validated against starts EMPTY on the first frame of every composition. An empty
 * list therefore means two very different things — "the collector has not delivered yet" versus
 * "the list loaded and really is empty (the last server was deleted)" — and `loaded` is what
 * tells them apart.
 */
class ModelsServerSelectionTest {

    private val home = ServerConfig("Home", "http://127.0.0.1:11434")
    private val work = ServerConfig("Work", "http://127.0.0.1:11435")

    @Test
    fun `no selection stays no selection`() {
        assertNull(modelsServerUrlAfterListLoad(null, listOf(home), loaded = true))
        assertNull(modelsServerUrlAfterListLoad(null, emptyList(), loaded = false))
    }

    @Test
    fun `before the list is delivered an empty list keeps the selection`() {
        // The first frame after a rotation: collector has not run, selection is restored.
        assertEquals(home.url, modelsServerUrlAfterListLoad(home.url, emptyList(), loaded = false))
    }

    @Test
    fun `a loaded list that still contains the selection keeps it`() {
        assertEquals(home.url, modelsServerUrlAfterListLoad(home.url, listOf(home, work), loaded = true))
    }

    @Test
    fun `a loaded list that no longer contains the selection clears it`() {
        // The server was deleted while another remains — evidence the selection is stale.
        assertNull(modelsServerUrlAfterListLoad(home.url, listOf(work), loaded = true))
    }

    @Test
    fun `a loaded list that is now empty clears the selection`() {
        // Deleting the LAST server: the list is empty, but it HAS loaded, so the selection is
        // stale and must be cleared (this is exactly what a bare `servers.isEmpty()` guard, which
        // cannot tell "empty" from "not loaded yet", would get wrong).
        assertNull(modelsServerUrlAfterListLoad(home.url, emptyList(), loaded = true))
    }
}
