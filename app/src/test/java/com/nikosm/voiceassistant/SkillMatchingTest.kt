package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skills trigger matching — the deterministic half of the feature.
 *
 * [matchedSkills] decides whether instruction text is injected into the system prompt, so it
 * is deliberately a PURE function of (message text, library): no service, no Android, no LLM.
 * That is what makes it testable here and what makes a skill's firing reproducible — the
 * same property `isImageGenerationRequest` relies on for image requests.
 *
 * The decisions these pin are the ones a user would notice being wrong:
 *  - prefix, case-insensitive matching (Q2), matching `isImageGenerationRequest` exactly;
 *  - ALL matching skills are returned in library order, never just the first (Q1);
 *  - a half-finished skill (no usable trigger, or blank instructions) never "fires";
 *  - a persona's enabled ids resolve against the live library, dropping dangling ones (Q8).
 */
class SkillMatchingTest {

    private fun skill(
        id: String,
        triggers: List<String> = listOf("summarize"),
        instructions: String = "Be concise."
    ) = Skill(id = id, name = "Skill $id", triggerPhrases = triggers, instructions = instructions)

    private val library = listOf(
        skill("s-summarize", listOf("summarize", "tl;dr")),
        skill("s-translate", listOf("translate")),
        skill("s-draw", listOf("create an image of"))
    )

    private fun names(matched: List<Skill>) = matched.map { it.id }

    // ---- Prefix + case-insensitivity, mirroring isImageGenerationRequest

    @Test fun `matches on a case-insensitive prefix`() {
        assertEquals(listOf("s-summarize"), names(matchedSkills("Summarize this", library)))
        assertEquals(listOf("s-summarize"), names(matchedSkills("SUMMARIZE this", library)))
        assertEquals(listOf("s-summarize"), names(matchedSkills("summarize this", library)))
    }

    @Test fun `tolerates leading and trailing whitespace on the message`() {
        assertEquals(listOf("s-summarize"), names(matchedSkills("   summarize this  ", library)))
    }

    @Test fun `tolerates whitespace around a stored trigger phrase`() {
        // A trigger typed with a stray trailing space in the editor must still fire.
        assertEquals(
            listOf("s"),
            names(matchedSkills("summarize this", listOf(skill("s", listOf("summarize  ")))))
        )
    }

    @Test fun `does NOT match a phrase that is not a prefix`() {
        // Q2: prefix matching, consistent with the existing image trigger. "please summarize"
        // deliberately does not fire — substring matching would have.
        assertTrue(matchedSkills("please summarize this", library).isEmpty())
    }

    @Test fun `does not match a trigger that only appears mid-sentence`() {
        assertTrue(matchedSkills("hey, summarize this", library).isEmpty())
    }

    @Test fun `any one of several triggers is enough`() {
        assertEquals(listOf("s-summarize"), names(matchedSkills("tl;dr this wall of text", library)))
    }


    // ---- Q1: multiple matches return ALL of them, in library order

    @Test fun `returns every matching skill in library order`() {
        val both = listOf(
            skill("s-first", listOf("go")),
            skill("s-second", listOf("go")),
            skill("s-third", listOf("go"))
        )
        assertEquals(listOf("s-first", "s-second", "s-third"), names(matchedSkills("go now", both)))
    }

    @Test fun `returns matches ordered by the library, not by trigger position`() {
        // The library order is the injection order, so a match must not jump ahead of an
        // earlier entry just because its trigger is shorter.
        val reordered = listOf(skill("s-translate", listOf("go")), skill("s-summarize", listOf("go now")))
        assertEquals(listOf("s-translate", "s-summarize"), names(matchedSkills("go now", reordered)))
    }

    // ---- Half-finished skills never fire

    @Test fun `a skill with no instructions never fires`() {
        val blank = listOf(skill("s-blank", listOf("summarize"), instructions = "   "))
        assertTrue(matchedSkills("summarize this", blank).isEmpty())
    }

    @Test fun `a skill with no trigger phrases never fires`() {
        val noTriggers = listOf(skill("s-none", emptyList(), instructions = "Be concise."))
        assertTrue(matchedSkills("summarize this", noTriggers).isEmpty())
    }

    @Test fun `blank trigger phrases are ignored rather than matching everything`() {
        // The classic bug this pins: an empty trigger is a prefix of EVERY string, so
        // `startsWith("")` is true for all input and a half-typed skill fires on everything.
        val halfTyped = listOf(skill("s-typed", listOf("", "   "), instructions = "Be concise."))
        assertTrue(matchedSkills("summarize this", halfTyped).isEmpty())
        assertTrue(matchedSkills("", halfTyped).isEmpty())
    }

    @Test fun `a blank trigger alongside a real one still only fires on the real one`() {
        val mixed = listOf(skill("s-mixed", listOf("", "summarize"), instructions = "Be concise."))
        assertEquals(listOf("s-mixed"), names(matchedSkills("summarize this", mixed)))
        assertTrue(matchedSkills("something else entirely", mixed).isEmpty())
    }

    // ---- Degenerate inputs

    @Test fun `a blank message matches nothing`() {
        assertTrue(matchedSkills("", library).isEmpty())
        assertTrue(matchedSkills("     ", library).isEmpty())
    }

    @Test fun `an empty library matches nothing`() {
        assertTrue(matchedSkills("summarize this", emptyList()).isEmpty())
    }

    @Test fun `does not crash on a library whose entries are all unusable`() {
        val unusable = listOf(skill("a", emptyList(), ""), skill("b", listOf("x"), ""))
        assertTrue(matchedSkills("summarize", unusable).isEmpty())
    }

    // ---- enabledSkillsFor: resolving a persona's ids against the live library (Q8)

    private fun personaWith(vararg ids: String) =
        Persona("P", Color(0xFF4ADE80), "[S] m", "sys", enabledSkillIds = ids.toList())

    @Test fun `resolves a persona's enabled ids to the live skills`() {
        assertEquals(listOf("s-summarize"), names(enabledSkillsFor(personaWith("s-summarize"), library)))
    }

    @Test fun `orders by the library, not by the persona's id order`() {
        // Q1: multi-match injection concatenates this list verbatim, so its order is
        // user-visible. `enabledSkillIds` is a Set and carries no order contract, so the
        // library is the only stable source of ordering — here the persona lists the ids in
        // the OPPOSITE order to the library, and the library order must win.
        assertEquals(
            listOf("s-summarize", "s-translate"),
            names(enabledSkillsFor(personaWith("s-translate", "s-summarize"), library))
        )
    }

    @Test fun `drops an id whose skill no longer exists`() {
        // Q8: a deleted skill must never inject anything, even before the list is pruned.
        assertEquals(
            listOf("s-summarize"),
            names(enabledSkillsFor(personaWith("s-summarize", "s-deleted"), library))
        )
    }

    @Test fun `a persona with no enabled skills resolves to nothing`() {
        assertTrue(enabledSkillsFor(personaWith(), library).isEmpty())
        assertTrue(enabledSkillsFor(personaWith(), emptyList()).isEmpty())
    }

    @Test fun `an empty library cannot resolve any id`() {
        assertTrue(enabledSkillsFor(personaWith("s-summarize"), emptyList()).isEmpty())
    }

    // ---- skillSuffixForTurn: the injection point (Q1/Q2/Q7)

    private fun suffix(text: String, persona: Persona, skills: List<Skill> = library) =
        skillSuffixForTurn(text, persona, skills)

    @Test fun `injects the instructions of a matching skill`() {
        assertEquals("Be concise.", suffix("summarize this", personaWith("s-summarize")))
    }

    @Test fun `injects nothing when no enabled skill matches`() {
        // The no-op case matters more than it looks: it is what leaves a room preamble
        // byte-for-byte intact on the turns where no skill fired.
        assertEquals("", suffix("what is the weather", personaWith("s-summarize")))
    }

    @Test fun `injects nothing for a persona with no skills enabled`() {
        assertEquals("", suffix("summarize this", personaWith()))
    }

    @Test fun `never injects a skill the persona has not enabled`() {
        // A disabled-but-matching skill must be invisible: matching is per-persona.
        assertEquals("", suffix("summarize this", personaWith("s-translate")))
    }

    @Test fun `injects ALL matching skills, never just the first`() {
        // Q1: a message may legitimately match several enabled skills ("summarize this as a
        // poem"), and all of them are injected — the multi-match warning is logged at the
        // injection point, not resolved by silently dropping the losers.
        val ordered = listOf(
            skill("s-a", listOf("do"), instructions = "FIRST"),
            skill("s-b", listOf("do"), instructions = "SECOND")
        )
        assertEquals("FIRST\n\nSECOND", suffix("do it", personaWith("s-a", "s-b"), ordered))
    }

    @Test fun `multi-match order is the library's, whatever the persona's id order`() {
        val ordered = listOf(
            skill("s-a", listOf("do"), instructions = "FIRST"),
            skill("s-b", listOf("do"), instructions = "SECOND"),
            skill("s-c", listOf("do"), instructions = "THIRD")
        )
        assertEquals(
            "FIRST\n\nSECOND\n\nTHIRD",
            suffix("do it", personaWith("s-c", "s-a", "s-b"), ordered)
        )
        // Same library, ids listed in a different order => byte-identical result. This is
        // the regression guard for enabledSkillIds being a Set: iterating it would make
        // the injected order arbitrary.
        assertEquals(
            "FIRST\n\nSECOND\n\nTHIRD",
            suffix("do it", personaWith("s-b", "s-c", "s-a"), ordered)
        )
    }

    @Test fun `a skill with blank instructions is not injected at all`() {
        val halfDone = listOf(skill("s-a", listOf("do"), instructions = "  "))
        assertEquals("", suffix("do it", personaWith("s-a"), halfDone))
    }

    @Test fun `instructions are trimmed and never blank-line padded`() {
        val padded = listOf(skill("s-a", listOf("do"), instructions = "\n  Be brief.  \n"))
        assertEquals("Be brief.", suffix("do it", personaWith("s-a"), padded))
    }

    @Test fun `an empty message injects nothing`() {
        assertEquals("", suffix("", personaWith("s-summarize")))
        assertEquals("", suffix("   ", personaWith("s-summarize")))
    }

    // ---- TurnContext.withSkills: composing with an existing suffix (Q7)
    //
    // A fake sink stands in for a room's, so these can assert the room invariants (the
    // preamble survives, the turn stays a ROOM turn) without binding the real service.

    private object SingleChatSinkForTest : TurnSink {
        override fun size() = 0
        override fun append(message: ChatMessage) = 0
        override fun updateAt(index: Int, transform: (ChatMessage) -> ChatMessage) = false
        override fun removeAt(index: Int) {}
        override fun peek(index: Int): ChatMessage? = null
    }

    private fun roomCtx(suffix: String = "ROOM PREAMBLE") =
        TurnContext(systemPromptSuffix = suffix, sink = SingleChatSinkForTest)

    @Test fun `appends skill text after an existing room preamble`() {
        assertEquals(
            "ROOM PREAMBLE\n\nBe concise.",
            roomCtx().withSkills("summarize this", personaWith("s-summarize"), library).systemPromptSuffix
        )
    }

    @Test fun `a non-matching turn leaves the room preamble exactly as it was`() {
        val ctx = roomCtx()
        assertEquals(ctx, ctx.withSkills("unrelated chatter", personaWith("s-summarize"), library))
    }

    @Test fun `a persona-less suffix still gets the skill text`() {
        assertEquals(
            "Be concise.",
            TurnContext.Default.withSkills("summarize this", personaWith("s-summarize"), library)
                .systemPromptSuffix
        )
    }

    @Test fun `withSkills does not mutate the context it was called on`() {
        // The base context belongs to the caller, and a room's turn context is rebuilt per
        // member — leaking skill text back into it would leak one member's skills into the
        // next member's turn, and into the next round.
        val ctx = roomCtx()
        ctx.withSkills("summarize this", personaWith("s-summarize"), library)
        assertEquals("ROOM PREAMBLE", ctx.systemPromptSuffix)
    }

    @Test fun `withSkills preserves history and sink`() {
        val ctx = TurnContext(
            history = listOf(ChatMessage("user", "hi")),
            systemPromptSuffix = "ROOM PREAMBLE",
            sink = SingleChatSinkForTest
        )
        val out = ctx.withSkills("summarize this", personaWith("s-summarize"), library)
        assertEquals(ctx.history, out.history)
        assertEquals(ctx.sink, out.sink)
        // A room turn must stay a ROOM turn: the single-chat ownership guard keys off this,
        // and a room member is by definition not the active persona.
        assertEquals(false, out.isSingleChat)
    }

    @Test fun `two skills compose into one suffix, not two`() {
        val ordered = listOf(
            skill("s-a", listOf("do"), instructions = "FIRST"),
            skill("s-b", listOf("do"), instructions = "SECOND")
        )
        assertEquals(
            "ROOM\n\nFIRST\n\nSECOND",
            roomCtx("ROOM").withSkills("do it", personaWith("s-a", "s-b"), ordered).systemPromptSuffix
        )
    }

    @Test fun `a blank base suffix does not leave a leading gap`() {
        val ordered = listOf(skill("s-a", listOf("do"), instructions = "FIRST"))
        assertEquals(
            "FIRST",
            roomCtx("").withSkills("do it", personaWith("s-a"), ordered).systemPromptSuffix
        )
    }

    // ---- shouldInjectSkills: the image / translator exclusions (Q6)

    @Test fun `a normal conversational turn may inject skills`() {
        assertTrue(shouldInjectSkills(personaWith(), "summarize this"))
    }

    @Test fun `an image-generator persona never injects skills`() {
        // Q6: image generation is not a conversational turn and has no system prompt for a
        // skill to ride in.
        val imageGen = personaWith("s-summarize").copy(isImageGenerator = true)
        assertFalse(shouldInjectSkills(imageGen, "summarize this"))
        assertEquals(
            TurnContext.Default,
            TurnContext.Default.withSkills("summarize this", imageGen, library)
        )
    }

    @Test fun `an image-requesting message never injects skills`() {
        // Even for an ordinary persona, an image request is routed away from the
        // conversational builders entirely.
        for (trigger in listOf("create an image of a cat", "draw me a dog", "generate an image of x", "make an image of y")) {
            assertFalse("'$trigger' should not inject skills", shouldInjectSkills(personaWith("s-summarize"), trigger))
        }
    }

    @Test fun `a translator never injects skills`() {
        // Q6: a translator's builders REPLACE the system prompt with one translation
        // instruction, so a skill's text would displace it.
        val translator = personaWith("s-summarize").copy(isTranslator = true, targetLanguage = "French")
        assertFalse(shouldInjectSkills(translator, "summarize this"))
        assertEquals(
            TurnContext.Default,
            TurnContext.Default.withSkills("summarize this", translator, library)
        )
    }

    @Test fun `the exclusions are decided by the same trigger list the router uses`() {
        // The guard delegates to the router's own predicate, so these must never diverge.
        for (text in listOf("create an image of a cat", "draw me", "hello there")) {
            assertEquals(
                "the skills guard and the router disagree about: $text",
                !isImageGenerationRequestText(text),
                shouldInjectSkills(personaWith("s-summarize"), text)
            )
        }
    }

    // ---- latestUserTurnText: which text a ROOM turn matches against (Q7)

    private fun userMsg(text: String) = RoomMessage(speakerName = RoomMessage.USER_SPEAKER, text = text)
    private fun personaMsg(name: String, text: String) = RoomMessage(speakerName = name, text = text)

    @Test fun `finds the user's message in a room transcript`() {
        assertEquals("summarize this", latestUserTurnText(listOf(userMsg("summarize this"))))
    }

    @Test fun `uses the LAST user turn, not the first`() {
        assertEquals(
            "summarize this",
            latestUserTurnText(listOf(userMsg("hello"), userMsg("summarize this")))
        )
    }

    @Test fun `skips intervening persona turns`() {
        // The ordinary shape of a round: the user asks, then members reply. A skill fired by
        // the question must still apply to every member answering it (Q7) — not just the
        // member whose turn happened to be first.
        val transcript = listOf(
            userMsg("summarize this"),
            personaMsg("Bob", "Sure."),
            personaMsg("Cara", "Here you go.")
        )
        assertEquals("summarize this", latestUserTurnText(transcript))
    }

    @Test fun `returns null when the transcript has no user turn`() {
        assertEquals(null, latestUserTurnText(emptyList()))
        assertEquals(null, latestUserTurnText(listOf(personaMsg("Bob", "hi"))))
    }

    @Test fun `a blank streaming placeholder is not treated as the user's words`() {
        assertEquals(
            "summarize this",
            latestUserTurnText(listOf(userMsg("summarize this"), userMsg("   ")))
        )
        assertEquals(null, latestUserTurnText(listOf(userMsg(""))))
    }

    @Test fun `the room's own turn instruction must never be what matched`() {
        // Guards the actual wiring decision. roomTurnInstruction is a fixed internal
        // instruction issued to every member of every round, so matching against it would
        // make a skill with any common trigger fire on EVERY turn in EVERY room. Only the
        // user's own words may match — here the two paths are fed deliberately different text
        // and must disagree.
        val instruction = roomTurnInstruction(
            Persona("Bob", Color(0xFF4ADE80), "[S] m", "sys"),
            Room(id = "r", name = "Room")
        )
        assertEquals("", skillSuffixForTurn(instruction, personaWith("s-summarize"), library))
        assertEquals("Be concise.", skillSuffixForTurn("summarize this", personaWith("s-summarize"), library))
    }

    @Test fun `text and room flows resolve identically for the same words`() {
        // The consistency claim (Q7): the same user sentence, resolved through the text
        // flow's entry point and through the room flow's, must inject the same text — the
        // room additionally keeps its preamble, it does not replace the skill injection.
        val persona = personaWith("s-summarize", "s-translate")
        val words = "translate this"
        val expected = skillSuffixForTurn(words, persona, library)

        val viaText = TurnContext.Default.withSkills(words, persona, library)
        assertEquals(expected, viaText.systemPromptSuffix)

        val transcript = listOf(userMsg(words), personaMsg("Bob", "ok"))
        val viaRoom = latestUserTurnText(transcript)
            ?.let { roomCtx().withSkills(it, persona, library) }
            ?: roomCtx()
        assertTrue(viaRoom.systemPromptSuffix.endsWith(expected))
        assertTrue(viaRoom.systemPromptSuffix.startsWith("ROOM PREAMBLE"))
    }
}
