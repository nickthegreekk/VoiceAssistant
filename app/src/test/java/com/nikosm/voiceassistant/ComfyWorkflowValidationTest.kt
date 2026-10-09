package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [validateComfyWorkflow]: it must accept exactly what a ComfyUI server can execute, and
 * reject everything else with a message that names the FIX rather than the fault.
 *
 * The editor-format case matters most in practice. The browser's own "Save" writes
 * `{"nodes":[…],"links":[…]}`, which looks like a workflow to the person who just exported it and
 * is unexecutable by the server, so the message has to say which export they need instead of
 * falling back to "isn't valid JSON".
 */
class ComfyWorkflowValidationTest {

    private fun messageOf(result: ComfyWorkflowValidation): String {
        assertTrue("expected Invalid, was $result", result is ComfyWorkflowValidation.Invalid)
        return (result as ComfyWorkflowValidation.Invalid).message
    }

    @Test
    fun theCapturedApiWorkflowIsAcceptedWithNoWarnings() {
        val fixture = ComfyFixtures.load("workflow_api.json", ComfyFixtures.SAMPLE_WORKFLOW)
        assertEquals(ComfyWorkflowValidation.Valid(), validateComfyWorkflow(fixture))
    }

    @Test
    fun theBrowserEditorsOwnSaveFormatIsRejectedWithTheExportInstruction() {
        val editorSaveFormat = """{"last_node_id":9,"nodes":[{"id":3,"type":"KSampler"}],"links":[]}"""
        assertEquals(
            COMFY_WORKFLOW_EDITOR_FORMAT_MESSAGE,
            messageOf(validateComfyWorkflow(editorSaveFormat))
        )
    }

    @Test
    fun anythingThatIsNotJsonIsRejectedAsJson() {
        assertEquals(COMFY_WORKFLOW_NOT_JSON_MESSAGE, messageOf(validateComfyWorkflow("{not json")))
    }

    @Test
    fun aJsonArrayIsNotAWorkflow() {
        assertEquals(COMFY_WORKFLOW_NOT_OBJECT_MESSAGE, messageOf(validateComfyWorkflow("[1,2,3]")))
    }

    @Test
    fun anEmptyObjectHasNoNodes() {
        assertEquals(COMFY_WORKFLOW_EMPTY_MESSAGE, messageOf(validateComfyWorkflow("{}")))
    }

    @Test
    fun blankTextIsRejectedAsEmptyRatherThanAsInvalidJson() {
        assertEquals(COMFY_WORKFLOW_EMPTY_MESSAGE, messageOf(validateComfyWorkflow("   ")))
    }

    @Test
    fun aNodeWithoutClassTypeNamesThatNode() {
        val message = messageOf(validateComfyWorkflow("""{"3":{"inputs":{}}}"""))
        assertEquals(comfyWorkflowNodeMessage("3", COMFY_WORKFLOW_NODE_NO_CLASS_TYPE), message)
    }

    @Test
    fun aNodeWithoutInputsNamesThatNode() {
        val message = messageOf(validateComfyWorkflow("""{"7":{"class_type":"CLIPTextEncode"}}"""))
        assertEquals(comfyWorkflowNodeMessage("7", COMFY_WORKFLOW_NODE_NO_INPUTS), message)
    }

    @Test
    fun aNonObjectNodeIsRejected() {
        val message = messageOf(validateComfyWorkflow("""{"3":"KSampler"}"""))
        assertEquals(comfyWorkflowNodeMessage("3", COMFY_WORKFLOW_NODE_NOT_OBJECT), message)
    }

    @Test
    fun aFullRequestBodyIsUnwrappedForTheConvenienceOfAPastedNetworkTab() {
        val body = """{"prompt":${ComfyFixtures.SAMPLE_WORKFLOW},"client_id":"abc-123"}"""
        assertEquals(ComfyWorkflowValidation.Valid(), validateComfyWorkflow(body))
    }

    @Test
    fun aWorkflowWithNoPromptTokenStillValidatesButWarns() {
        val noPrompt = """{"3":{"class_type":"KSampler","inputs":{"seed":1}}}"""
        assertEquals(
            ComfyWorkflowValidation.Valid(listOf(ComfyWorkflowWarning.NO_PROMPT_FIELD)),
            validateComfyWorkflow(noPrompt)
        )
    }

    @Test
    fun sizeTokensValidateButWarnThatTheyAreUnsupported() {
        // The workflow owns its size in v1, so %WIDTH%/%HEIGHT% are left alone and the user is
        // told on save. Both warnings can apply at once, so they are ordered as declared.
        val sized = """{"5":{"class_type":"EmptyLatentImage","inputs":{"width":"%WIDTH%","height":"%HEIGHT%"}},"6":{"class_type":"CLIPTextEncode","inputs":{"text":"%PROMPT%"}}}"""
        assertEquals(
            ComfyWorkflowValidation.Valid(listOf(ComfyWorkflowWarning.UNSUPPORTED_SIZE_TOKEN)),
            validateComfyWorkflow(sized)
        )
        val bothMissing = """{"5":{"class_type":"EmptyLatentImage","inputs":{"width":"%WIDTH%"}}}"""
        assertEquals(
            ComfyWorkflowValidation.Valid(
                listOf(ComfyWorkflowWarning.NO_PROMPT_FIELD, ComfyWorkflowWarning.UNSUPPORTED_SIZE_TOKEN)
            ),
            validateComfyWorkflow(bothMissing)
        )
    }

    @Test
    fun anOversizedWorkflowIsRejectedWithoutBeingParsed() {
        val huge = """{"3":{"class_type":"KSampler","inputs":{"text":"%PROMPT%","pad":"${"x".repeat(COMFY_MAX_WORKFLOW_CHARS)}"}}}"""
        assertEquals(COMFY_WORKFLOW_TOO_LARGE_MESSAGE, messageOf(validateComfyWorkflow(huge)))
    }
}
