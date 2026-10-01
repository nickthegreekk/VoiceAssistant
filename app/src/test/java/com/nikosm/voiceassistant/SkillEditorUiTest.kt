package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Skills UI's pure half: how the trigger field round-trips, and the exact copy the list
 * rows, the editor's inert-skill warnings and the delete confirmation render.
 *
 * These are the strings a user actually reads, and `parseTriggerPhrases` decides what the
 * matcher will see — so both are asserted verbatim rather than structurally. Everything here
 * is free of Compose and of the service, which is what makes it testable on the JVM.
 */
class SkillEditorUiTest {

    private fun skill(
        id: String = "s1",
        name: String = "Summarizer",
        triggers: List<String> = listOf("summarize this"),
        instructions: String = "Be concise."
    ) = Skill(id = id, name = name, triggerPhrases = triggers, instructions = instructions)

    private fun persona(name: String, vararg skillIds: String) =
        Persona(name, Color(0xFF4ADE80), "[S] m", "sys", enabledSkillIds = skillIds.toList())

    // ---- trigger phrases: one per line, trimmed, blanks dropped

    @Test fun `parses one phrase per line`() {
        assertEquals(
            listOf("summarize this", "tl;dr", "make it short"),
            parseTriggerPhrases("summarize this\ntl;dr\nmake it short")
        )
    }

    @Test fun `trims each line and drops blank ones`() {
        // A trigger is matched with startsWith, so an untrimmed "  summarize this" would
        // never fire — silently, and for every message. This is the guard against that.
        assertEquals(
            listOf("summarize this", "tl;dr"),
            parseTriggerPhrases("  summarize this  \n\n\ttl;dr\t\n   \n")
        )
    }

    @Test fun `accepts Windows line endings`() {
        assertEquals(listOf("a", "b"), parseTriggerPhrases("a\r\nb"))
    }

    @Test fun `an empty or blank field yields no triggers`() {
        assertEquals(emptyList<String>(), parseTriggerPhrases(""))
        assertEquals(emptyList<String>(), parseTriggerPhrases("\n  \n\n"))
    }

    @Test fun `preserves the user's phrase order`() {
        assertEquals(listOf("zebra", "apple"), parseTriggerPhrases("zebra\napple"))
    }

    @Test fun `phrases round-trip through the editor field`() {
        val formatted = formatTriggerPhrases(listOf("summarize this", "tl;dr", "  padded  "))
        assertEquals("summarize this\ntl;dr\n  padded  ", formatted)
        // The round-trip goes through the parse, because that trim is exactly what makes
        // the stored form safe to match against.
        assertEquals(listOf("summarize this", "tl;dr", "padded"), parseTriggerPhrases(formatted))
    }

    @Test fun `formatting an empty list is an empty field`() {
        assertEquals("", formatTriggerPhrases(emptyList()))
    }

    // ---- row summaries

    @Test fun `the trigger summary joins phrases in order`() {
        assertEquals("a · b", skillTriggerSummary(skill(triggers = listOf("a", "b"))))
    }

    @Test fun `a skill with no triggers says so instead of showing nothing`() {
        assertEquals(NO_TRIGGERS_WARNING, skillTriggerSummary(skill(triggers = emptyList())))
    }

    @Test fun `usage summary counts the personas that enabled the skill`() {
        val target = skill(id = "s1")
        assertEquals("Not used by any persona", skillUsageSummary(target, emptyList()))
        assertEquals("Used by 1 persona", skillUsageSummary(target, listOf(persona("Bob", "s1"))))
        assertEquals(
            "Used by 2 personas",
            skillUsageSummary(target, listOf(persona("Bob", "s1"), persona("Cara", "s1")))
        )
    }

    @Test fun `usage summary ignores a different skill's id`() {
        val target = skill(id = "s1")
        val personas = listOf(persona("Bob", "s-other"), persona("Cara"))
        assertEquals("Not used by any persona", skillUsageSummary(target, personas))
    }

    @Test fun `user names lists only the personas that enabled it, in list order`() {
        val target = skill(id = "s1")
        val personas = listOf(persona("Cara"), persona("Bob", "s1"), persona("Dee", "s1"))
        assertEquals(listOf("Bob", "Dee"), skillUserNames(target, personas))
    }

    // ---- delete confirmation copy

    @Test fun `the delete dialog names the single affected persona`() {
        val text = skillDeleteConfirmText(skill(name = "Summarizer"), listOf("Bob"))
        assertTrue(text, text.contains("Summarizer"))
        assertTrue(text, text.contains("Bob"))
    }

    @Test fun `the delete dialog names two affected personas`() {
        assertTrue(skillDeleteConfirmText(skill(), listOf("Bob", "Cara")).contains("Bob and Cara"))
    }

    @Test fun `the delete dialog counts rather than listing when many are affected`() {
        // A wall of names is unreadable in a dialog; the count still answers the question.
        assertTrue(skillDeleteConfirmText(skill(), listOf("A", "B", "C", "D")).contains("4 personas"))
    }

    @Test fun `the delete copy does not claim a loss when nobody uses the skill`() {
        // The dialog is not even shown in that case, so its text must not imply one.
        val text = skillDeleteConfirmText(skill(), emptyList())
        assertFalse(text, text.contains("will lose it"))
    }

    // ---- the editor's commit

    @Test fun `commits a trimmed name and the parsed trigger list`() {
        val commit = skillEditorCommit(
            skill = skill(name = "Old", triggers = listOf("old")),
            name = "  Summarizer  ",
            triggerText = "  summarize this \n\n tl;dr ",
            instructions = "Be brief."
        )
        assertNotNull(commit)
        assertEquals("Summarizer", commit!!.name)
        assertEquals(listOf("summarize this", "tl;dr"), commit.triggerPhrases)
        assertEquals("Be brief.", commit.instructions)
    }

    @Test fun `the commit never changes the skill's id`() {
        // The id is what every persona references; a commit that minted a new one would
        // orphan every enabled reference to this skill.
        assertEquals("stable-id", skillEditorCommit(skill(id = "stable-id"), "Renamed", "a", "b")!!.id)
    }

    @Test fun `an unchanged editor commits nothing`() {
        val base = skill(name = "Summarizer", triggers = listOf("a", "b"), instructions = "x")
        assertEquals(null, skillEditorCommit(base, "Summarizer", "a\nb", "x"))
    }

    @Test fun `a blank name commits nothing`() {
        assertEquals(null, skillEditorCommit(skill(), "   ", "a", "x"))
    }

    @Test fun `a skill with no triggers is still committable`() {
        // Deliberately allowed: a half-written skill is legal, and the UI warns rather than
        // blocks. Refusing to save it would throw away the user's work-in-progress.
        val commit = skillEditorCommit(skill(triggers = listOf("a")), "Named", "", "text")
        assertNotNull(commit)
        assertEquals(emptyList<String>(), commit!!.triggerPhrases)
    }
}
