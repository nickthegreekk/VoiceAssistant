package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The one join that turns "the active persona name" into "the active persona".
 *
 * Pure and JVM-testable on purpose: this is the logic that was previously written out twice
 * with two different fallbacks (`AssistantService.currentPersona` returning null, and the VAD
 * speech-end path substituting `firstOrNull()`), and it is now the single definition both use,
 * plus the one `currentPersonaFlow` exposes to the UI.
 *
 * The identity assertion is the point of the second test: resolution returns the STORED
 * instance, not a copy, so a consumer that observes it sees exactly what the service holds —
 * which is what makes "the service's selection" and "the persona the UI acts on" the same
 * object rather than two objects that merely agree today.
 */
class PersonaSelectionResolutionTest {

    private fun persona(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[HomeBox] llama3",
        systemPrompt = "You are $name.",
        isCloud = false
    )

    @Test fun `no selection resolves to nothing`() {
        assertNull(resolveCurrentPersona(null, listOf(persona("A"))))
    }

    @Test fun `an empty list resolves to nothing`() {
        assertNull(resolveCurrentPersona("A", emptyList()))
    }

    @Test fun `a name that is not in the list resolves to nothing`() {
        assertNull(resolveCurrentPersona("B", listOf(persona("A"), persona("C"))))
    }

    @Test fun `a present name resolves to the stored instance itself`() {
        val a = persona("A")
        val b = persona("B")
        val resolved = resolveCurrentPersona("A", listOf(a, b))
        assertSame("resolution must hand back the stored object, never a copy", a, resolved)
    }

    @Test fun `resolution is by name, not by position`() {
        val a = persona("A")
        val b = persona("B")
        val resolved = resolveCurrentPersona("B", listOf(a, b))
        assertSame(b, resolved)
    }

    @Test fun `a renamed selection stops resolving under the old name`() {
        // Renames are handled upstream: AssistantService.updatePersona re-points
        // currentPersonaName when the ACTIVE persona is renamed. This pins the lookup half —
        // the old name must not silently keep matching something else.
        val renamed = persona("Robert")
        assertNull(resolveCurrentPersona("Bob", listOf(renamed)))
        assertSame(renamed, resolveCurrentPersona("Robert", listOf(renamed)))
    }

    @Test fun `a duplicated name resolves to the first occurrence, deterministically`() {
        // Nothing enforces persona name uniqueness (unlike server configs, which reject
        // duplicate URLs), so this is a real state. Both consumers now agree on the first
        // match; previously the UI could display the other one.
        val first = persona("Dup")
        val second = persona("Dup")
        assertSame(first, resolveCurrentPersona("Dup", listOf(first, second)))
        assertSame(first, resolveCurrentPersona("Dup", listOf(first, second)))
    }
}
