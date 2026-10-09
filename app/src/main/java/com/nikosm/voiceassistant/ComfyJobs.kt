package com.nikosm.voiceassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

// ---------------------------------------------------------------------------
// ComfyUI jobs: submitting, polling, and reading what came back.
//
// Pure in / pure out — no Android, no HTTP, no service — so every branch the server can answer
// with is testable against a real captured `/history` entry: a pending job, a finished job, a
// failed job, a job with several outputs, and a job whose history entry has no status block.
//
// The shapes below are taken from the ComfyUI source, not from guesswork:
//  - `POST /prompt` → `{"prompt_id", "number", "node_errors"}`; a validation failure is a 400 with
//    `{"error": {...}, "node_errors": {<node id>: {errors, dependent_outputs, class_type}}}`.
//  - `GET /history/{id}` → `{}` while the job is UNKNOWN *or* still pending (the two are
//    indistinguishable — `PromptQueue.get_history` returns `{}` until `task_done` writes the
//    entry), and afterwards `{<id>: {prompt, outputs, status}}` where status is
//    `{status_str, completed, messages}` and a failed run still lands in history.
//  - outputs are `{<node id>: {"images": [{filename, subfolder, type}]}}`, type `output` for
//    `SaveImage` and `temp` for `PreviewImage`.
// ---------------------------------------------------------------------------

/** First poll delay. */
internal const val COMFY_POLL_INITIAL_MS = 500L

/** Ceiling on the poll delay — a cold model load can take minutes, so polling must stay cheap. */
internal const val COMFY_POLL_MAX_MS = 2_000L

/** Multiplier applied to the poll delay until it reaches [COMFY_POLL_MAX_MS]. */
internal const val COMFY_POLL_BACKOFF = 1.5

/**
 * Overall budget for one job. Generous on purpose: a cold Flux/SDXL load on a CPU or a small GPU
 * legitimately takes minutes, and failing early would be wrong far more often than it would help.
 */
internal const val COMFY_POLL_TIMEOUT_MS = 10 * 60 * 1000L

/**
 * How long an empty history is tolerated before asking `/queue` whether the job still exists.
 *
 * `{}` from `/history` means "unknown or pending", so an empty history alone cannot tell a slow
 * cold load from a job that was cancelled or dropped. After this long, `/queue` disambiguates.
 */
internal const val COMFY_LOST_JOB_AFTER_MS = 5_000L

/** Hard cap on a downloaded image, enforced while streaming to a temp file. */
internal const val COMFY_MAX_IMAGE_BYTES = 25 * 1024 * 1024

/**
 * How many consecutive transport failures (a dropped connection, a refused connect, a timeout)
 * while polling mean the server is gone rather than briefly busy.
 *
 * A transport failure is not a job failure: ComfyUI keeps running and a single blip must be
 * tolerated. Several in a row inside [COMFY_TRANSPORT_FAILURE_WINDOW_MS] are the server itself
 * being unreachable, and saying so at once beats waiting out the full [COMFY_POLL_TIMEOUT_MS] on
 * a socket that will never answer.
 */
internal const val COMFY_TRANSPORT_FAILURES_BEFORE_DEAD = 4

/** Window the [COMFY_TRANSPORT_FAILURES_BEFORE_DEAD] failures must fall inside to count as dead. */
internal const val COMFY_TRANSPORT_FAILURE_WINDOW_MS = 8_000L

// --- messages (constants so the UI and tests cannot drift) -----------------------------------

internal const val COMFY_NO_WORKFLOW_MESSAGE =
    "This ComfyUI entry has no workflow. Add one in Settings → Servers → Image Gen."
internal const val COMFY_NO_PROMPT_ID_MESSAGE =
    "ComfyUI accepted the workflow but returned no prompt id."
internal const val COMFY_JOB_LOST_MESSAGE =
    "The job disappeared from ComfyUI's queue without finishing. Check the ComfyUI console."
internal const val COMFY_NO_IMAGE_MESSAGE =
    "ComfyUI finished the workflow but produced no image."
internal const val COMFY_JOB_FAILED_MESSAGE =
    "The workflow failed before producing an image."
internal const val COMFY_IMAGE_TOO_LARGE_MESSAGE =
    "The image ComfyUI returned is larger than 25 MB."

/**
 * Appended to ComfyUI's OWN error text when the run failed on the GPU.
 *
 * The server's words are always kept — they name the node and the exact fault — and this only adds
 * the two things a user cannot read off that text: that a retry needs a ComfyUI restart, and that a
 * tiled VAE decode is the usual way around it.
 */
internal const val COMFY_GPU_FAILURE_HINT =
    "ComfyUI's GPU failed. Restart ComfyUI before retrying, and consider VAE Decode (Tiled) in the " +
        "workflow."

/** Substrings (case-insensitive) that mark a ComfyUI error as a GPU failure. */
internal val COMFY_GPU_ERROR_MARKERS = listOf("illegal memory access", "hipError", "out of memory")

/** The operator-visible wording when the ComfyUI address stops answering while polling. */
internal fun comfyUnreachableMessage(address: String): String =
    "ComfyUI at $address stopped responding"

/**
 * ComfyUI's error text, with [COMFY_GPU_FAILURE_HINT] appended when it names a GPU failure.
 *
 * Pure and idempotent-by-inspection: a message that does not carry a marker is returned unchanged,
 * so every non-GPU failure reads exactly as it always did.
 */
internal fun withComfyGpuHint(message: String): String =
    if (COMFY_GPU_ERROR_MARKERS.any { message.contains(it, ignoreCase = true) }) {
        "$message $COMFY_GPU_FAILURE_HINT"
    } else {
        message
    }

internal fun comfyJobTimeoutMessage(minutes: Long): String =
    "The image job did not finish within $minutes minutes."

internal fun comfyHttpErrorMessage(code: Int, message: String): String {
    val suffix = message.trim()
    return if (suffix.isEmpty()) "ComfyUI returned HTTP $code" else "ComfyUI returned HTTP $code — $suffix"
}

// --- models -----------------------------------------------------------------------------------

/** One entry of a node's `images` list — exactly the tuple `/view` needs to fetch it. */
internal data class ComfyOutputImage(val filename: String, val subfolder: String, val type: String)

/** The outcome of `POST /prompt`. */
internal sealed interface ComfyPromptSubmit {
    data class Accepted(val promptId: String) : ComfyPromptSubmit

    /** [message] is user-facing: it names the node and the server's own words. */
    data class Rejected(val message: String) : ComfyPromptSubmit
}

/** What `/history/{id}` says about one job. */
internal sealed interface ComfyJobState {
    /** No entry yet — the job is unknown or still running. Keep polling. */
    object Pending : ComfyJobState

    data class Finished(val image: ComfyOutputImage) : ComfyJobState

    data class Failed(val message: String) : ComfyJobState
}

/** Where `/queue` says the job is. */
internal enum class ComfyQueuePosition {
    RUNNING,
    PENDING,

    /** Neither list holds it — the only state that justifies giving up early. */
    ABSENT,

    /** The queue body did not say (unparseable, or missing a list). Never a reason to give up. */
    UNKNOWN
}

private val ComfyJobJson = Json

/**
 * A fresh seed for one generation.
 *
 * Bounded by 2^53 rather than Long.MAX_VALUE: ComfyUI's samplers take a 64-bit int, but its own
 * frontend and several JS/JSON round trips only hold integers exactly up to 2^53, so staying under
 * it keeps a seed that survives a trip through the browser intact.
 */
internal fun randomComfySeed(): Long =
    java.util.concurrent.ThreadLocalRandom.current().nextLong(0L, 1L shl 53)

/** The body `POST /prompt` expects: the workflow as an OBJECT, plus a client id. */
internal fun buildComfySubmitBody(workflowJson: String, clientId: String = UUID.randomUUID().toString()): String {
    val workflow = ComfyJobJson.parseToJsonElement(workflowJson)
    return buildJsonObject {
        put("prompt", workflow)
        put("client_id", JsonPrimitive(clientId))
    }.toString()
}

/**
 * The body Stop posts to `/interrupt`.
 *
 * Carries the prompt id even though the caller has ALREADY confirmed the id is in `queue_running`:
 * current ComfyUI answers a targeted interrupt by only calling `interrupt_processing()` when that id
 * is genuinely the running one, so this is the belt to the `/queue` check's braces and can never
 * disturb a job started from the browser.
 */
internal fun buildComfyInterruptBody(promptId: String): String =
    buildJsonObject { put("prompt_id", JsonPrimitive(promptId)) }.toString()

/** The body Stop posts to `/queue` for a job that is still pending: delete exactly that id. */
internal fun buildComfyQueueDeleteBody(promptId: String): String =
    buildJsonObject {
        put("delete", buildJsonArray { add(JsonPrimitive(promptId)) })
    }.toString()

/** The body the optional "free memory after each image" switch posts to `/free`. */
internal fun buildComfyFreeBody(): String = buildJsonObject {
    put("unload_models", JsonPrimitive(true))
    put("free_memory", JsonPrimitive(true))
}.toString()

/**
 * Reads `POST /prompt`'s answer.
 *
 * A 400 is rendered from `node_errors` FIRST, because that is the actionable half: it names the
 * node and carries the server's own explanation ("Value not in list", "Cannot execute because node
 * X does not exist"), where the sibling `error` object only says the outputs failed validation.
 */
internal fun parseComfySubmitResponse(statusCode: Int, body: String): ComfyPromptSubmit {
    val root = runCatching { ComfyJobJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    if (statusCode !in 200..299) return ComfyPromptSubmit.Rejected(comfyRejectionMessage(statusCode, root))
    val promptId = (root?.get("prompt_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (promptId.isNullOrBlank()) return ComfyPromptSubmit.Rejected(COMFY_NO_PROMPT_ID_MESSAGE)
    return ComfyPromptSubmit.Accepted(promptId)
}

/**
 * Reads `GET /history/{prompt_id}`.
 *
 * An ABSENT `status` block is not an error: the rule is that a history entry with an image is
 * finished, whatever else is missing. An entry with neither status nor image is treated as still
 * pending rather than broken, so a partially written entry cannot fail a healthy job.
 */
internal fun parseComfyJobState(body: String, promptId: String): ComfyJobState {
    val root = runCatching { ComfyJobJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val entry = root?.get(promptId) as? JsonObject ?: return ComfyJobState.Pending
    val image = findComfyImage(entry["outputs"] as? JsonObject)
    val status = entry["status"] as? JsonObject
        ?: return image?.let { ComfyJobState.Finished(it) } ?: ComfyJobState.Pending

    val completed = (status["completed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
    val statusStr = (status["status_str"] as? JsonPrimitive)?.content.orEmpty()
    val failed = statusStr.equals("error", ignoreCase = true) || !completed
    return when {
        // A run that failed after a SaveImage already ran still has an image. Deliver it: the
        // user asked for a picture, and refusing it would be pedantry.
        failed && image != null -> ComfyJobState.Finished(image)
        failed -> ComfyJobState.Failed(withComfyGpuHint(comfyStatusMessage(status)))
        image != null -> ComfyJobState.Finished(image)
        else -> ComfyJobState.Failed(COMFY_NO_IMAGE_MESSAGE)
    }
}

/**
 * Reads `GET /queue` — `{"queue_running": [...], "queue_pending": [...]}`, each item the queue
 * tuple `[number, prompt_id, prompt, extra_data, outputs_to_execute]`.
 *
 * Both keys must be present before [ComfyQueuePosition.ABSENT] is reported: "this job is gone" is
 * only safe to conclude from a queue that really answered, never from a body that happened not to
 * parse (a proxy page, a truncated reply). Anything else is UNKNOWN, which the caller polls
 * through.
 */
internal fun parseComfyQueuePosition(body: String, promptId: String): ComfyQueuePosition {
    val root = runCatching { ComfyJobJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return ComfyQueuePosition.UNKNOWN
    val running = root["queue_running"] as? JsonArray ?: return ComfyQueuePosition.UNKNOWN
    val pending = root["queue_pending"] as? JsonArray ?: return ComfyQueuePosition.UNKNOWN
    if (queueHolds(running, promptId)) return ComfyQueuePosition.RUNNING
    if (queueHolds(pending, promptId)) return ComfyQueuePosition.PENDING
    return ComfyQueuePosition.ABSENT
}

/**
 * The first image any node produced, in the order the server listed its outputs.
 *
 * That order is ComfyUI's own — the executor's cache order, which is execution order, so a recorder
 * node appears after the nodes whose images it saved. It is NOT re-sorted by node id: reordering
 * what the server sent would be inventing a policy it does not have.
 */
internal fun findComfyImage(outputs: JsonObject?): ComfyOutputImage? {
    if (outputs == null) return null
    for ((_, node) in outputs) {
        val images = (node as? JsonObject)?.get("images") as? JsonArray ?: continue
        for (candidate in images) {
            val image = candidate as? JsonObject ?: continue
            val filename = (image["filename"] as? JsonPrimitive)?.content
            if (filename.isNullOrBlank()) continue
            return ComfyOutputImage(
                filename = filename,
                // SaveImage writes "" for the root, and /view accepts an empty subfolder.
                subfolder = (image["subfolder"] as? JsonPrimitive)?.content.orEmpty(),
                type = (image["type"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: "output"
            )
        }
    }
    return null
}

private fun queueHolds(queue: JsonArray, promptId: String): Boolean = queue.any { item ->
    val tuple = item as? JsonArray ?: return@any false
    (tuple.getOrNull(1) as? JsonPrimitive)?.content == promptId
}

private fun comfyRejectionMessage(statusCode: Int, root: JsonObject?): String {
    root ?: return comfyHttpErrorMessage(statusCode, "")
    (root["node_errors"] as? JsonObject)?.entries?.firstOrNull()?.let { (nodeId, value) ->
        val node = value as? JsonObject
        val error = (node?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject
        if (error != null) {
            val classType = (node["class_type"] as? JsonPrimitive)?.content
            val label = if (classType.isNullOrBlank()) "Node $nodeId" else "Node $nodeId ($classType)"
            val message = (error["message"] as? JsonPrimitive)?.content.orEmpty()
            val details = (error["details"] as? JsonPrimitive)?.content.orEmpty()
            val text = joinMessageDetails(message, details)
            if (text.isNotBlank()) return "$label: $text"
        }
    }
    (root["error"] as? JsonObject)?.let { error ->
        val message = (error["message"] as? JsonPrimitive)?.content.orEmpty()
        val details = (error["details"] as? JsonPrimitive)?.content.orEmpty()
        val text = joinMessageDetails(message, details)
        if (text.isNotBlank()) return text
    }
    return comfyHttpErrorMessage(statusCode, "")
}

/** The failing run's own words, from `status.messages`' `exception_message` where there is one. */
private fun comfyStatusMessage(status: JsonObject): String {
    val messages = status["messages"] as? JsonArray ?: return COMFY_JOB_FAILED_MESSAGE
    for (element in messages) {
        val payload = (element as? JsonArray)?.getOrNull(1) as? JsonObject ?: continue
        val exception = (payload["exception_message"] as? JsonPrimitive)?.content
        if (!exception.isNullOrBlank()) {
            val node = (payload["node_id"] as? JsonPrimitive)?.content
            val type = (payload["node_type"] as? JsonPrimitive)?.content
            val where = when {
                node != null && type != null -> " (node $node, $type)"
                node != null -> " (node $node)"
                else -> ""
            }
            return "$exception$where"
        }
        val message = (payload["message"] as? JsonPrimitive)?.content
        if (!message.isNullOrBlank()) return message
    }
    return COMFY_JOB_FAILED_MESSAGE
}

private fun joinMessageDetails(message: String, details: String): String = when {
    message.isBlank() -> details
    details.isBlank() -> message
    else -> "$message — $details"
}
