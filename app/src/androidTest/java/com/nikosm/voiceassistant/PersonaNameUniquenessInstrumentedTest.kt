package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Persona NAME uniqueness — the IN-MEMORY write path, proven on-device.
 *
 * The reported bug: the header button's translator lookup missed an existing persona named
 * "Translator" and added a second one beside it. [PersonaNameGuardTest] pins the pure lookup
 * and the persistence chokepoint; only a real service can show that the guard in
 * `AssistantService.addPersona` / `updatePersona` actually HOLDS, because the service owns
 * the live `personas` flow the picker renders from — a duplicate that reached it would be
 * visible to the user even if storage corrected it later.
 *
 * These tests deliberately do NOT call `switchPersona`/`clearMessages`: the button's
 * select-and-clear half is orthogonal to name uniqueness and destructive to the device's
 * in-memory conversation, so only its "add if missing" decision is replayed here.
 *
 * `_personas` is private, so this file uses the same public-API snapshot/restore dance as
 * [SpontaneousWriteGuardInstrumentedTest]: snapshot in @Before, remove-all-then-re-add in
 * @After.
 */
@RunWith(AndroidJUnit4::class)
class PersonaNameUniquenessInstrumentedTest {

    private companion object {
        const val TRANSLATOR = "Translator"
        const val OWNER = "ProbeNameOwner"
        const val CREATOR = "ProbeNameCreator"
        const val VICTIM = "ProbeNameVictim"
        const val RENAMER = "ProbeNameRenamer"
        val PROBES = listOf(OWNER, CREATOR, VICTIM, RENAMER)
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var personasBefore: List<Persona> = emptyList()
    private var currentPersonaBefore: String? = null
    private var messagesOwnerBefore: String? = null

    @Before
    fun bindAssistantService() {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { /* not expected here */ }
        }
        connection = conn
        assertTrue(
            "bindService() refused to connect to AssistantService",
            context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!

        personasBefore = service.personas.value
        currentPersonaBefore = service.currentPersonaName
        messagesOwnerBefore = service.messagesOwnerName

        // Drop leftovers from a crashed run (probe personas AND any persona holding the
        // translator's name), so the tests below start from a known list.
        mainSync {
            val stale = service.personas.value.indices.reversed().filter { i ->
                val name = service.personas.value[i].name
                name in PROBES || name == TRANSLATOR
            }
            stale.forEach { service.removePersona(it) }
            service.saveSettings()
        }
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching {
            instrumentation.runOnMainSync {
                service.personas.value.indices.reversed().forEach { service.removePersona(it) }
                personasBefore.forEach { service.addPersona(it) }
                service.currentPersonaName = currentPersonaBefore
                service.messagesOwnerName = messagesOwnerBefore
                // addPersona/updatePersona persist, so restoring memory alone is not enough.
                service.saveSettings()
            }
        }
        PROBES.forEach { runCatching { service.settingsManager.deletePersonaHistory(it) } }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun persona(
        name: String,
        prompt: String = "You are $name.",
        translator: Boolean = false
    ) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[ProbeOllamaNames] llama3",
        systemPrompt = prompt,
        isTranslator = translator
    )

    private fun stored(name: String): Persona =
        service.personas.value.firstOrNull { it.name == name }
            ?: error("persona '$name' is not in the service's list")

    private fun indexOf(name: String): Int =
        service.personas.value.indexOfFirst { it.name == name }
            .also { require(it >= 0) { "persona '$name' is not in the service's list" } }

    private fun translatorCount(): Int = service.personas.value.count { it.name == TRANSLATOR }

    /**
     * Replays the header button's "add the translator if it is missing" decision, and
     * nothing else — see the class comment on why switching is excluded.
     */
    private fun pressTranslatorButton() {
        val existing = existingTranslatorPersona(service.personas.value)
        if (existing == null) service.addPersona(TRANSLATOR_PERSONA)
    }

    // ---- the chokepoint itself --------------------------------------------------------

    @Test fun `addPersona refuses a second persona carrying an existing name`() {
        mainSync { service.addPersona(persona(OWNER, prompt = "first")) }
        val sizeAfterFirstAdd = service.personas.value.size

        mainSync { service.addPersona(persona(OWNER, prompt = "second")) }

        assertEquals(
            "addPersona is the chokepoint every creation path flows through; no caller may " +
                "slip a duplicate name past it",
            sizeAfterFirstAdd,
            service.personas.value.size
        )
        assertEquals(1, service.personas.value.count { it.name == OWNER })
    }

    @Test fun `a manually created persona reusing an existing name neither duplicates nor overwrites it`() {
        // The user path: the editor's first commit calls addPersona with the typed name.
        // The two possible wrong outcomes are a duplicate row and clobbering the incumbent;
        // this pins that neither happens.
        mainSync { service.addPersona(persona(OWNER, prompt = "incumbent prompt")) }
        val incumbent = stored(OWNER)

        mainSync { service.addPersona(persona(OWNER, prompt = "brand new persona", translator = true)) }

        assertEquals(1, service.personas.value.count { it.name == OWNER })
        val after = stored(OWNER)
        assertEquals("the incumbent must keep its prompt", incumbent.systemPrompt, after.systemPrompt)
        assertEquals("the incumbent must keep its template flags", incumbent.isTranslator, after.isTranslator)
    }

    // ---- the reported translator duplication -------------------------------------------

    @Test fun `the live translator lookup finds a Translator persona that lacks the flag`() {
        // The precondition of the bug: a persona NAMED "Translator" whose isTranslator was
        // never set (the flag comes from a template; a rename does not set it).
        val namedOnly = persona(TRANSLATOR, prompt = "user-named translator", translator = false)
        mainSync { service.addPersona(namedOnly) }

        assertSame(
            "the button must recognise its translator by name, not by a stale flag",
            namedOnly,
            existingTranslatorPersona(service.personas.value)
        )
    }

    @Test fun `the header button never adds a second Translator when one already exists`() {
        mainSync { service.addPersona(persona(TRANSLATOR, prompt = "user-named translator")) }
        val sizeBefore = service.personas.value.size

        // Repeated presses also cover the stale-list race: the picker renders from a flow
        // snapshot, so a second tap can be handled before the first add is collected.
        mainSync { repeat(3) { pressTranslatorButton() } }

        assertEquals("exactly one persona may be named Translator", 1, translatorCount())
        assertEquals("the button must not grow the persona list", sizeBefore, service.personas.value.size)
        assertEquals(
            "and the survivor is the pre-existing one",
            "user-named translator",
            stored(TRANSLATOR).systemPrompt
        )
    }

    @Test fun `the header button still creates the translator when none exists`() {
        // The inverse control: a lookup that always matched would pass the test above.
        assertEquals(0, translatorCount())
        mainSync { pressTranslatorButton() }
        assertEquals(1, translatorCount())
        assertEquals(TRANSLATOR_PERSONA.name, stored(TRANSLATOR).name)
    }

    // ---- the rename side of the same invariant -----------------------------------------

    // No apostrophe in this name: dex cannot represent one in a method name.
    @Test fun `a rename onto an existing name is refused and the victim history survives`() {
        mainSync {
            service.addPersona(persona(RENAMER))
            service.addPersona(persona(VICTIM))
        }
        val transcript = listOf(
            ChatMessage(role = "user", text = "victim's chat"),
            ChatMessage(role = "assistant", text = "still here")
        )
        service.settingsManager.savePersonaMessages(VICTIM, transcript)

        // The editor's commit carries every field, so this is what typing an existing name
        // into the name box produces.
        mainSync { service.updatePersona(indexOf(RENAMER), stored(RENAMER).copy(name = VICTIM)) }

        assertEquals("the refused rename must leave the renamer alone", RENAMER, stored(RENAMER).name)
        assertEquals(1, service.personas.value.count { it.name == VICTIM })
        assertEquals(
            "the rename must not have migrated history onto the victim's name key",
            transcript,
            service.settingsManager.getPersonaMessages(VICTIM)
        )
    }

    @Test fun `a rename to a fresh name still works`() {
        // The inverse control: a guard that refused every rename would pass the test above.
        mainSync { service.addPersona(persona(RENAMER)) }
        mainSync { service.updatePersona(indexOf(RENAMER), stored(RENAMER).copy(name = CREATOR)) }
        assertTrue("the renamer must be gone under its old name", service.personas.value.none { it.name == RENAMER })
        assertEquals(CREATOR, stored(CREATOR).name)
    }
}
