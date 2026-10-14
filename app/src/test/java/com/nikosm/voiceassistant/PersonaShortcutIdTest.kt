package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id that ties a pinned home-screen icon to the persona it opens.
 *
 * This one string does three jobs at once, and each of them is invisible until it breaks:
 *
 *  - it is the deep link's key, so it must round-trip the persona name exactly;
 *  - it is the launcher's identity for the icon, so two personas must never share one;
 *  - it is what `removeLongLivedShortcuts` is handed on rename and delete, so it must be
 *    reproducible from the name alone, with no stored table to consult.
 *
 * The id is deliberately the raw name behind a prefix rather than a hash. A hash would make
 * every assertion below merely probabilistic, and — unlike
 * [spontaneousNotificationId], where a collision only merges two notification cards — a
 * collision here would make one persona's home-screen icon unreachable.
 */
class PersonaShortcutIdTest {

    @Test
    fun `the id is the name behind a namespace prefix`() {
        assertEquals("persona_Cara", personaShortcutId("Cara"))
    }

    @Test
    fun `the same name always produces the same id`() {
        // Removal depends on this: the id is recomputed at delete time from the name alone.
        assertEquals(personaShortcutId("Cara"), personaShortcutId("Cara"))
    }

    @Test
    fun `distinct names never share an id`() {
        val names = listOf(
            "Cara", "cara", "CARA", "Cara ", " Cara", "Cara2", "Car",
            "Bob", "Élodie", "张伟", "a b", "persona_Cara"
        )
        val ids = names.map { personaShortcutId(it) }
        assertEquals(
            "two personas sharing an id would share one home-screen icon",
            names.size,
            ids.toSet().size
        )
    }

    @Test
    fun `a name that is a prefix of another keeps its own id`() {
        // Not a hash, so this is exact rather than merely probable — worth pinning precisely
        // because a prefix collision is the failure mode a hash would have risked.
        assertNotEquals(personaShortcutId("Car"), personaShortcutId("Cara"))
    }

    @Test
    fun `the id is never empty, whatever the name`() {
        // ShortcutManager rejects an empty id outright, so even a blank name has to yield a
        // usable one rather than throwing at pin time.
        assertTrue(personaShortcutId("").isNotEmpty())
        assertTrue(personaShortcutId("   ").isNotEmpty())
    }

    @Test
    fun `the prefix survives a name that already looks prefixed`() {
        // Guards the namespacing itself: a persona literally named "persona_Cara" must not
        // alias the id of a persona named "Cara".
        assertNotEquals(personaShortcutId("Cara"), personaShortcutId("persona_Cara"))
        assertEquals("persona_persona_Cara", personaShortcutId("persona_Cara"))
    }
}
