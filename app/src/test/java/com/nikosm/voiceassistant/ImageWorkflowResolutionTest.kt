package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Image Character precedence (`resolveImageWorkflow`) and the import-naming rule
 * (`uniqueImageWorkflowName`).
 *
 * The behaviour that matters: a persona's chosen character WINS over the server entry's own
 * workflow — that is the whole point of two personas sharing one ComfyUI server and painting
 * different faces — while a dangling id or a blank template degrades to the entry's workflow
 * rather than failing a generation. Pure, so no service and no device are needed.
 */
class ImageWorkflowResolutionTest {

    private val face = ImageWorkflow(
        id = "face",
        name = "Cyberealistic",
        workflow = """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"her, %PROMPT%"}}}"""
    )
    private val nsfw = ImageWorkflow(
        id = "nsfw",
        name = "NSFW",
        workflow = """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"her, %PROMPT%"}}}"""
    )
    private val library = listOf(face, nsfw)

    private val entryWorkflow = """{"9":{"class_type":"SaveImage","inputs":{}}}"""

    @Test
    fun thePersonasCharacterWinsOverTheEntryWorkflow() {
        assertEquals(face.workflow, resolveImageWorkflow("face", library, entryWorkflow))
    }

    @Test
    fun twoPersonasOnOneServerGetDifferentFaces() {
        // The feature in one line: same server, same credentials, different character.
        assertEquals(face.workflow, resolveImageWorkflow("face", library, entryWorkflow))
        assertEquals(nsfw.workflow, resolveImageWorkflow("nsfw", library, entryWorkflow))
    }

    @Test
    fun aDanglingIdFallsBackToTheEntryWorkflowRatherThanFailing() {
        assertEquals(entryWorkflow, resolveImageWorkflow("deleted", library, entryWorkflow))
    }

    @Test
    fun noSelectionUsesTheEntryWorkflow() {
        assertEquals(entryWorkflow, resolveImageWorkflow(null, library, entryWorkflow))
    }

    @Test
    fun aBlankCharacterFallsBackToTheEntryWorkflow() {
        val blank = ImageWorkflow(id = "blank", name = "Blank", workflow = "   ")
        assertEquals(entryWorkflow, resolveImageWorkflow("blank", listOf(blank), entryWorkflow))
    }

    @Test
    fun neitherSourceYieldsNull() {
        assertNull(resolveImageWorkflow(null, library, null))
        assertNull(resolveImageWorkflow(null, library, ""))
        assertNull(resolveImageWorkflow("deleted", library, null))
    }

    // ------------------------------------------------------------ import naming

    @Test
    fun aFreeNameIsKeptVerbatimAndTrimmed() {
        assertEquals("Brand New", uniqueImageWorkflowName("  Brand New  ", library))
    }

    @Test
    fun aTakenNameGetsANumericSuffix() {
        assertEquals("Cyberealistic 2", uniqueImageWorkflowName("Cyberealistic", library))
        assertEquals(
            "Cyberealistic 3",
            uniqueImageWorkflowName(
                "Cyberealistic",
                library + ImageWorkflow(id = "x", name = "Cyberealistic 2", workflow = "{}")
            )
        )
    }

    @Test
    fun aBlankNameBecomesCharacter() {
        assertEquals("Character", uniqueImageWorkflowName("", library))
        assertEquals(
            "Character 2",
            uniqueImageWorkflowName(
                "   ",
                library + ImageWorkflow(id = "c", name = "Character", workflow = "{}")
            )
        )
    }
}
