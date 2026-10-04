package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure halves of step 2: name uniqueness, and the `"[Server] model"` rewrite that
 * a rename depends on.
 *
 * The awkward cases are the ones worth pinning, because both are real model names in the
 * wild: brackets inside the model part of a GGUF name, and one server name that is a
 * prefix of another.
 */
class ServerIdentityTest {

    private fun persona(name: String, model: String, spontaneous: Boolean = false) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = model,
        systemPrompt = "x",
        isCloud = false,
        spontaneousEnabled = spontaneous
    )

    // ------------------------------------------------------- prefix extraction

    @Test
    fun `the prefix is everything up to the first bracket-space`() {
        assertEquals("Main", serverPrefixOf("[Main] llama3"))
        assertEquals("5800x", serverPrefixOf("[5800x] hf.co/x/y:q4_k_m"))
        // A GGUF name containing "] " must NOT be mistaken for the end of the prefix.
        assertEquals("Main", serverPrefixOf("[Main] Qwen[2]-7B:q4_k_m"))
        assertNull(serverPrefixOf("llama3"))
        assertNull(serverPrefixOf("[NoSpace]llama3"))
        assertNull(serverPrefixOf(""))
    }

    // ------------------------------------------------------------ name uniqueness

    @Test
    fun `a duplicate name is rejected`() {
        val existing = listOf("Main", "ziptie")
        assertTrue(isServerNameTaken("Main", existing))
        assertFalse(isServerNameTaken("Work", existing))
    }

    @Test
    fun `uniqueness ignores case and surrounding whitespace`() {
        val existing = listOf("Main")
        assertTrue("case must not create a second meaning", isServerNameTaken("main", existing))
        assertTrue(isServerNameTaken("MAIN", existing))
        assertTrue("a trailing space is invisible inside the stored string", isServerNameTaken(" Main ", existing))
    }

    @Test
    fun `a name that is a prefix of another is not a collision`() {
        val existing = listOf("Main", "Main2")
        assertTrue("Main is genuinely in the list", isServerNameTaken("Main", existing))
        assertTrue(isServerNameTaken("Main2", existing))
        // The prefix trap: these are DIFFERENT names and must both be free.
        assertFalse("Mai is not Main", isServerNameTaken("Mai", existing))
        assertFalse("Main3 is not Main2", isServerNameTaken("Main3", existing))
        assertFalse("ain is not Main", isServerNameTaken("ain", existing))
    }

    @Test
    fun `excluding the entry being edited allows saving without a name change`() {
        val existing = listOf("Main", "Work")
        assertFalse(
            "renaming a server to its own current name is not a collision",
            isServerNameTaken("Main", existing, exclude = listOf("Main"))
        )
        assertTrue(
            "but another server still blocks it",
            isServerNameTaken("Work", existing, exclude = listOf("Main"))
        )
    }

    @Test
    fun `renaming one member of a pre-existing duplicate pair out is allowed`() {
        // Two servers both called "Main" (from a backup written before the guard).
        val existing = listOf("Main", "Main", "Work")
        // Excluding by name drops every entry with that name, so "Main" is free to keep...
        assertFalse(isServerNameTaken("Main", existing, exclude = listOf("Main")))
        // ...and, the point of the case, renaming one OUT of the pair is allowed too.
        assertFalse(isServerNameTaken("Main3", existing, exclude = listOf("Main")))
        // Without the exclusion the pair still blocks a third party.
        assertTrue(isServerNameTaken("Main", existing))
    }

    @Test
    fun `a blank candidate never collides with a real name`() {
        // Two blanks compare equal, which is consistent and harmless; what matters is
        // that a blank is not confused with a real server.
        assertTrue(isServerNameTaken("", listOf("", "Main")))
        assertFalse(isServerNameTaken("", listOf("Main")))
        assertFalse(isServerNameTaken("Main", listOf("", "Main2"), exclude = listOf("Main")))
    }

    // --------------------------------------------------------------- the rewrite

    @Test
    fun `a rename rewrites the prefix and nothing else`() {
        assertEquals("[Work] llama3", rewriteServerPrefix("[Main] llama3", "Main", "Work"))
        assertEquals(
            "[Work] hf.co/x/y-GGUF:q4_k_m",
            rewriteServerPrefix("[Main] hf.co/x/y-GGUF:q4_k_m", "Main", "Work")
        )
    }

    @Test
    fun `a rename leaves a model with no prefix alone`() {
        assertEquals("llama3", rewriteServerPrefix("llama3", "Main", "Work"))
    }

    @Test
    fun `a rename does not touch a different server's model`() {
        assertEquals("[Main] llama3", rewriteServerPrefix("[Main] llama3", "Work", "Other"))
    }

    @Test
    fun `renaming Main must not touch Main2`() {
        val models = listOf("[Main] a:1b", "[Main2] b:2b")
        assertEquals(
            listOf("[Work] a:1b", "[Main2] b:2b"),
            rewriteServerPrefixes(models, "Main", "Work")
        )
    }

    @Test
    fun `a model name containing brackets is preserved intact`() {
        // The prefix ends at the FIRST "] "; everything after it is the model.
        assertEquals(
            "[Work] hf.co/x/Qwen[2]-7B-GGUF:q4_k_m",
            rewriteServerPrefix("[Main] hf.co/x/Qwen[2]-7B-GGUF:q4_k_m", "Main", "Work")
        )
    }

    @Test
    fun `renaming to the same name is a no-op`() {
        assertEquals("[Main] a:1b", rewriteServerPrefix("[Main] a:1b", "Main", "Main"))
        assertEquals(
            listOf("[Main] a:1b"),
            rewriteServerPrefixes(listOf("[Main] a:1b"), "Main", "Main")
        )
    }

    @Test
    fun `an untrimmed stored model still rewrites`() {
        assertEquals("[Work] llama3", rewriteServerPrefix("  [Main] llama3  ", "Main", "Work"))
    }

    // ------------------------------------------------------- personas end to end

    @Test
    fun `only the affected persona changes, and only its model`() {
        val before = listOf(
            persona("A", "[Main] llama3", spontaneous = true),
            persona("B", "[Main2] llama3"),
            persona("C", "bare-model")
        )
        val after = rewritePersonasServerPrefix(before, "Main", "Work")
        assertEquals("[Work] llama3", after[0].model)
        assertEquals("[Main2] llama3", after[1].model)
        assertEquals("bare-model", after[2].model)
        assertEquals("the name is untouched", "A", after[0].name)
        assertTrue(
            "spontaneousEnabled must survive: the server still exists, only renamed",
            after[0].spontaneousEnabled
        )
        assertTrue(
            "an unaffected persona must be the identical instance, not a copy",
            after[1] === before[1]
        )
    }
}
