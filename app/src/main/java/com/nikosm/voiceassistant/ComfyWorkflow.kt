package com.nikosm.voiceassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull

// ---------------------------------------------------------------------------
// ComfyUI workflow templates: validation and prompt filling.
//
// A ComfyUI workflow travels as ONE JSON object whose keys are node ids and whose values are
// `{"class_type": <string>, "inputs": {…}}`. That is the API format — the file ComfyUI writes
// from "Workflow → Export (API)". It is NOT the format the browser editor saves: that one is
// `{"nodes": [...], "links": [...], ...}` and the server cannot execute it, so it is rejected
// with a message that says which export the user needs.
//
// Everything here is PURE: no Android surface, no HTTP, no service. That is what makes the token
// substitution and the seed rules testable against a real exported workflow, and what keeps the
// "no per-model code" promise — nothing in this file knows about SD1.5, SDXL or Flux. The
// template decides where a prompt goes; Celeste only fills what the template asked for, and the
// workflow keeps ownership of steps, sampler and size.
// ---------------------------------------------------------------------------

/** Placeholder for the text the user typed (or the voice prompt). */
internal const val COMFY_TOKEN_PROMPT = "%PROMPT%"

/** Placeholder for Celeste's fixed negative prompt ([IMAGE_GEN_NEGATIVE_PROMPT]). */
internal const val COMFY_TOKEN_NEGATIVE = "%NEGATIVE%"

/** Placeholder for a fresh random seed. Becomes a JSON NUMBER, never a string. */
internal const val COMFY_TOKEN_SEED = "%SEED%"

/**
 * Size tokens that are deliberately NOT supported in v1.
 *
 * The workflow owns its size, and a hidden 512 default would be wrong for SDXL (and for any
 * graph with a different base resolution). A template that still carries these is left alone and
 * the user is warned on save, so a size they typed by hand is never silently overridden.
 */
internal val COMFY_UNSUPPORTED_SIZE_TOKENS = listOf("%WIDTH%", "%HEIGHT%")

/**
 * Input names whose literal numeric value Celeste re-randomises on every run, so Regenerate (and
 * a second attempt at the same prompt) cannot return the same image just because the exported
 * template pinned one seed. Applied only to plain numbers that are object properties; a LINK
 * (a `["<node id>", <output index>]` array) is never touched, and a `%SEED%` string is handled by
 * the token rule instead.
 */
internal val COMFY_SEED_INPUT_NAMES = setOf("seed", "noise_seed")

/** Cap on a pasted/picked workflow so a pathological paste cannot bloat the encrypted prefs. */
internal const val COMFY_MAX_WORKFLOW_CHARS = 1_000_000

// --- validation messages (constants so tests can assert the user-facing words) -------------

internal const val COMFY_WORKFLOW_EMPTY_MESSAGE =
    "The workflow is empty — paste a workflow you exported with Workflow → Export (API)."
internal const val COMFY_WORKFLOW_NOT_JSON_MESSAGE = "This isn't valid JSON."
internal const val COMFY_WORKFLOW_NOT_OBJECT_MESSAGE =
    "A ComfyUI workflow must be a JSON object of node ids."
internal const val COMFY_WORKFLOW_EDITOR_FORMAT_MESSAGE =
    "This is the ComfyUI editor's save format (it has \"nodes\" and \"links\"). " +
        "Re-export it with Workflow → Export (API) and paste that instead."
internal const val COMFY_WORKFLOW_NODE_NOT_OBJECT = "not a node object."
internal const val COMFY_WORKFLOW_NODE_NO_CLASS_TYPE = "missing \"class_type\"."
internal const val COMFY_WORKFLOW_NODE_NO_INPUTS = "missing its \"inputs\" object."
internal const val COMFY_WORKFLOW_TOO_LARGE_MESSAGE =
    "That workflow is too large (over 1 MB). Trim unused nodes and export again."

internal fun comfyWorkflowNodeMessage(nodeId: String, problem: String) = "Node $nodeId: $problem"

/** A non-fatal problem worth telling the user about before they generate. */
internal enum class ComfyWorkflowWarning {
    /**
     * No field in the workflow contains `%PROMPT%`, so the text the user types would be ignored.
     * A warning rather than an error: the template may be intentionally prompt-free (an
     * img2img/ControlNet graph with the prompt baked in), and blocking it would be wrong.
     */
    NO_PROMPT_FIELD,

    /**
     * The template carries `%WIDTH%`/`%HEIGHT%`, which Celeste does not fill: the workflow owns its
     * size, and guessing a default would be wrong for anything that is not 512×512. The token is
     * left in place so nothing is silently changed, and the user is told.
     */
    UNSUPPORTED_SIZE_TOKEN
}

/** The outcome of [validateComfyWorkflow]. */
internal sealed interface ComfyWorkflowValidation {
    data class Valid(val warnings: List<ComfyWorkflowWarning> = emptyList()) : ComfyWorkflowValidation

    /** [message] is shown verbatim to the user, so it must name the fix, not just the fault. */
    data class Invalid(val message: String) : ComfyWorkflowValidation
}

private val ComfyJson = Json

private const val COMFY_PROMPT_WRAPPER_KEY = "prompt"

private val COMFY_PROMPT_WRAPPER_KEYS =
    setOf(COMFY_PROMPT_WRAPPER_KEY, "client_id", "extra_data", "number", "front")

/**
 * Whether [text] is a workflow the ComfyUI server can execute.
 *
 * Accepts the API format directly, and also a full request body (`{"prompt": {…}, "client_id":
 * …}`) by unwrapping its `prompt` key — that is what a user who grabbed the body from a network
 * tab will paste, and the outer keys of a request body (`prompt`, `client_id`, `extra_data`,
 * `number`, `front`) can never collide with node ids, which are numeric strings.
 */
internal fun validateComfyWorkflow(text: String): ComfyWorkflowValidation {
    if (text.isBlank()) return ComfyWorkflowValidation.Invalid(COMFY_WORKFLOW_EMPTY_MESSAGE)
    if (text.length > COMFY_MAX_WORKFLOW_CHARS) {
        return ComfyWorkflowValidation.Invalid(COMFY_WORKFLOW_TOO_LARGE_MESSAGE)
    }
    val parsed = runCatching { ComfyJson.parseToJsonElement(text) }.getOrNull()
        ?: return ComfyWorkflowValidation.Invalid(COMFY_WORKFLOW_NOT_JSON_MESSAGE)
    if (parsed !is JsonObject) {
        return ComfyWorkflowValidation.Invalid(COMFY_WORKFLOW_NOT_OBJECT_MESSAGE)
    }
    val root = comfyWorkflowRoot(parsed)
    if (looksLikeEditorFormat(root)) {
        return ComfyWorkflowValidation.Invalid(COMFY_WORKFLOW_EDITOR_FORMAT_MESSAGE)
    }
    if (root.isEmpty()) return ComfyWorkflowValidation.Invalid(COMFY_WORKFLOW_EMPTY_MESSAGE)

    for ((nodeId, node) in root) {
        if (node !is JsonObject) {
            return ComfyWorkflowValidation.Invalid(
                comfyWorkflowNodeMessage(nodeId, COMFY_WORKFLOW_NODE_NOT_OBJECT)
            )
        }
        val classType = node["class_type"] as? JsonPrimitive
        if (classType == null || !classType.isString || classType.content.isBlank()) {
            return ComfyWorkflowValidation.Invalid(
                comfyWorkflowNodeMessage(nodeId, COMFY_WORKFLOW_NODE_NO_CLASS_TYPE)
            )
        }
        if (node["inputs"] !is JsonObject) {
            return ComfyWorkflowValidation.Invalid(
                comfyWorkflowNodeMessage(nodeId, COMFY_WORKFLOW_NODE_NO_INPUTS)
            )
        }
    }

    val warnings = buildList {
        if (!containsStringToken(root, COMFY_TOKEN_PROMPT)) add(ComfyWorkflowWarning.NO_PROMPT_FIELD)
        if (COMFY_UNSUPPORTED_SIZE_TOKENS.any { containsStringToken(root, it) }) {
            add(ComfyWorkflowWarning.UNSUPPORTED_SIZE_TOKEN)
        }
    }
    return ComfyWorkflowValidation.Valid(warnings)
}

/**
 * The workflow object inside [obj]: the object itself, or the `prompt` value of a full request
 * body. See [validateComfyWorkflow] for why the wrapper keys are an allow-list: a real workflow
 * keys its nodes by numeric id, so nothing inside one can look like a request body.
 */
private fun comfyWorkflowRoot(obj: JsonObject): JsonObject {
    val wrapped = obj[COMFY_PROMPT_WRAPPER_KEY]
    if (wrapped is JsonObject && obj.keys.all { it in COMFY_PROMPT_WRAPPER_KEYS }) return wrapped
    return obj
}

/** The browser editor's own save format — never executable, always worth a specific message. */
private fun looksLikeEditorFormat(obj: JsonObject): Boolean = obj["nodes"] is JsonArray

private fun containsStringToken(element: JsonElement, token: String): Boolean = when (element) {
    is JsonObject -> element.values.any { containsStringToken(it, token) }
    is JsonArray -> element.any { containsStringToken(it, token) }
    is JsonPrimitive -> element.isString && element.content.contains(token, ignoreCase = true)
    else -> false
}

// --- filling the prompt ---------------------------------------------------------------------

/** Everything Celeste supplies to a template. [seed] is a parameter so this stays pure/testable. */
internal data class ComfyPromptValues(
    val prompt: String,
    val negative: String = IMAGE_GEN_NEGATIVE_PROMPT,
    val seed: Long
)

/**
 * The workflow to POST to `/prompt`: [template] with its tokens filled in.
 *
 * Replacement is TYPE-AWARE, walking the parsed tree rather than doing a string search on the raw
 * JSON: a string whose whole value is `%SEED%` becomes a JSON NUMBER, because the server validates
 * `seed` as an integer and a quoted "12345" is rejected. A token embedded in a larger string is
 * replaced inside that string. Link arrays, sizes and unrelated numbers are left untouched, and —
 * when the template has no `%SEED%` — every literal numeric `seed` / `noise_seed` property is set
 * to [ComfyPromptValues.seed], so a Regenerate cannot return the template's pinned seed image
 * again.
 *
 * Returns [template] unchanged when it does not parse, so a caller with a validated template and
 * a caller without one both get something safe to send.
 */
internal fun buildComfyPromptWorkflow(template: String, values: ComfyPromptValues): String {
    val parsed = runCatching { ComfyJson.parseToJsonElement(template) }.getOrNull()
    val root = (parsed as? JsonObject)?.let { comfyWorkflowRoot(it) } ?: return template
    return substituteElement(root, key = null, values = values).toString()
}

private fun substituteElement(
    element: JsonElement,
    key: String?,
    values: ComfyPromptValues
): JsonElement = when (element) {
    is JsonObject -> buildJsonObject {
        for ((childKey, child) in element) put(childKey, substituteElement(child, childKey, values))
    }
    // The key is deliberately dropped inside an array: a link is ["<node id>", <output index>],
    // so propagating "seed" in here would rewrite the output index of a linked seed.
    is JsonArray -> buildJsonArray {
        for (child in element) add(substituteElement(child, key = null, values = values))
    }
    is JsonPrimitive -> when {
        element.isString -> substituteString(element.content, values)
        isSeedField(key) && element.doubleOrNull != null -> JsonPrimitive(values.seed)
        else -> element
    }
    else -> element
}

private fun substituteString(value: String, values: ComfyPromptValues): JsonPrimitive {
    if (value.trim().equals(COMFY_TOKEN_SEED, ignoreCase = true)) {
        return JsonPrimitive(values.seed)
    }
    if (!value.contains('%')) return JsonPrimitive(value)
    val replaced = value
        .replace(COMFY_TOKEN_PROMPT, values.prompt, ignoreCase = true)
        .replace(COMFY_TOKEN_NEGATIVE, values.negative, ignoreCase = true)
        .replace(COMFY_TOKEN_SEED, values.seed.toString(), ignoreCase = true)
    return JsonPrimitive(replaced)
}

private fun isSeedField(key: String?) = key != null && key.lowercase() in COMFY_SEED_INPUT_NAMES



