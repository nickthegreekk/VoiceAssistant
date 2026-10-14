package com.nikosm.voiceassistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// ---------------------------------------------------------------------------
// The ComfyUI generation flow.
//
// A1111 answers one request with the finished image; ComfyUI takes a workflow, queues it, runs it
// whenever the GPU is free, and only then produces a file. So this path is submit → poll →
// download:
//
//   1. POST /prompt with the filled-in workflow   (a validation failure is a 400 with node_errors)
//   2. GET /history/{id} until it has the entry   (with /queue consulted once, to tell a slow cold
//      load apart from a job that vanished)
//   3. GET /view for the first image the run produced
//
// Every request goes through the shared builder, so it carries the entry's credentials exactly as
// the A1111 path and the Test Connection probe do. The finished file is handed to
// [publishGeneratedImage], the same tail the A1111 path uses.
// ---------------------------------------------------------------------------

/**
 * Runs one ComfyUI generation and publishes the image, or throws with a message meant for the user.
 *
 * [workflow] is the template to submit — the persona's Image Character, or the entry's own
 * `comfyWorkflow` when the persona never picked one (resolved by [resolveImageWorkflow], which is
 * the ONE place the precedence lives). Defaulted to the entry's template, so a caller with no
 * persona context (and every existing test) behaves exactly as before.
 *
 * Cancellation is honoured at every `delay`, and each blocking read sits inside
 * `withContext(Dispatchers.IO)`, so cancelling the calling job (the Stop path) stops the polling and
 * never leaves a half-written file behind — the download only gets its final name after a complete
 * read.
 */
internal suspend fun AssistantService.performComfyImageGeneration(
    prompt: String,
    config: ServerConfig,
    startTime: Long,
    workflow: String? = config.comfyWorkflow
) {
    val template = workflow
    if (template.isNullOrBlank()) throw Exception(COMFY_NO_WORKFLOW_MESSAGE)

    // Re-validated here rather than trusted: the dialog refuses a malformed template, but a restored
    // backup or a hand-edited prefs blob can still carry one, and a clear message beats a 400.
    val validation = validateComfyWorkflow(template)
    if (validation is ComfyWorkflowValidation.Invalid) throw Exception(validation.message)

    val workflow = buildComfyPromptWorkflow(
        template,
        ComfyPromptValues(
            prompt = prompt,
            negative = IMAGE_GEN_NEGATIVE_PROMPT,
            seed = randomComfySeed()
        )
    )

    // Register this run so Stop can cancel the poll and stop the server-side job. The config is set
    // before submit (a Stop during submit still cancels this coroutine); the prompt id is added the
    // moment the server answers. Clearing in `finally` covers success, failure and cancellation
    // alike, so a finished run never leaves a stale handle for Stop to act on.
    comfyGenJob = currentCoroutineContext().job
    comfyGenConfig = config
    try {
        val promptId = when (val submit = submitComfyPrompt(config, buildComfySubmitBody(workflow))) {
            is ComfyPromptSubmit.Accepted -> submit.promptId
            is ComfyPromptSubmit.Rejected -> throw Exception(submit.message)
        }
        comfyGenPromptId = promptId

        val image = awaitComfyJob(config, promptId)
        val file = downloadComfyImage(config, image)
        publishGeneratedImage(prompt, file, startTime)
        // Optional, off by default, and deliberately last: the image is already saved, so a /free
        // that fails (or is rejected by an older server) must not change what the user sees.
        if (config.freeComfyMemoryAfterImage) freeComfyMemory(config)
    } finally {
        comfyGenJob = null
        comfyGenPromptId = null
        comfyGenConfig = null
    }
}

private class ComfyHttpResult(val code: Int, val body: String, val message: String)

private suspend fun AssistantService.submitComfyPrompt(config: ServerConfig, body: String): ComfyPromptSubmit {
    val result = comfyJson(config, COMFY_PROMPT_PATH, method = "POST", body = body)
    // A 404/405 from /prompt on an address that answers /sdapi/v1/sd-models is an A1111 server on
    // the ComfyUI list. Naming that beats "ComfyUI returned HTTP 404", and it is the whole reason a
    // generation can fail here with the address plainly reachable and healthy.
    imageGenBackendMismatchHint(config, result.code)?.let { throw ImageGenBackendMismatchException(it) }
    return parseComfySubmitResponse(result.code, result.body)
}

/**
 * Polls `/history/{id}` until the run finishes.
 *
 * `{}` means "unknown or pending" — the server cannot tell those apart — so the loop keeps polling
 * through it. After [COMFY_LOST_JOB_AFTER_MS] it asks `/queue` ONCE: if the job is in neither list
 * it is never coming back, and failing then with a clear message beats waiting out the full
 * [COMFY_POLL_TIMEOUT_MS]. A queue that cannot be read is not evidence of anything, so an UNKNOWN
 * answer simply resumes polling.
 *
 * A TRANSPORT failure (a dropped connection, a refused connect, a timeout) is not a job failure:
 * one blip is tolerated and polling continues. [COMFY_TRANSPORT_FAILURES_BEFORE_DEAD] of them in a
 * row inside [COMFY_TRANSPORT_FAILURE_WINDOW_MS] mean the address itself is gone, and the loop
 * fails at once with [comfyUnreachableMessage] rather than polling a dead socket for ten minutes.
 * A successful answer resets the streak.
 */
private suspend fun AssistantService.awaitComfyJob(config: ServerConfig, promptId: String): ComfyOutputImage {
    val startedAt = System.currentTimeMillis()
    val deadline = startedAt + COMFY_POLL_TIMEOUT_MS
    var interval = COMFY_POLL_INITIAL_MS
    var queueChecked = false
    var transportFailures = 0
    var firstFailureAt = 0L

    while (true) {
        if (System.currentTimeMillis() > deadline) {
            throw Exception(comfyJobTimeoutMessage(COMFY_POLL_TIMEOUT_MS / 60_000L))
        }
        val history = try {
            comfyJson(config, "$COMFY_HISTORY_PATH/$promptId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageGenAuthException) {
            throw e
        } catch (e: IOException) {
            val now = System.currentTimeMillis()
            if (transportFailures == 0) firstFailureAt = now
            transportFailures++
            if (transportFailures >= COMFY_TRANSPORT_FAILURES_BEFORE_DEAD &&
                now - firstFailureAt <= COMFY_TRANSPORT_FAILURE_WINDOW_MS
            ) {
                throw Exception(comfyUnreachableMessage(normalizeGatewayUrl(config.url)))
            }
            delay(interval)
            interval = minOf((interval * COMFY_POLL_BACKOFF).toLong(), COMFY_POLL_MAX_MS)
            continue
        }
        transportFailures = 0

        when (val state = parseComfyJobState(history.body, promptId)) {
            is ComfyJobState.Finished -> return state.image
            is ComfyJobState.Failed -> throw Exception(state.message)
            is ComfyJobState.Pending -> {
                if (!queueChecked && System.currentTimeMillis() - startedAt >= COMFY_LOST_JOB_AFTER_MS) {
                    queueChecked = true
                    val position = try {
                        parseComfyQueuePosition(comfyJson(config, COMFY_QUEUE_PATH).body, promptId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ImageGenAuthException) {
                        throw e
                    } catch (e: IOException) {
                        // A queue that does not answer is not evidence the job is gone.
                        ComfyQueuePosition.UNKNOWN
                    }
                    if (position == ComfyQueuePosition.ABSENT) throw Exception(COMFY_JOB_LOST_MESSAGE)
                }
            }
        }
        delay(interval)
        interval = minOf((interval * COMFY_POLL_BACKOFF).toLong(), COMFY_POLL_MAX_MS)
    }
}

/**
 * Stop path for a ComfyUI generation (called from `stopEverything`).
 *
 * Cancels the local poll FIRST — that alone is what guarantees no file is written — then, when the
 * job's prompt id is known, best-effort tells the server to stop. The server side is deliberately
 * queue-FIRST: a bodyless `/interrupt` is GLOBAL, so it is only sent once `/queue` has confirmed OUR
 * id is the running one; a pending job is removed with a targeted `/queue` delete; anything else
 * (absent, or a queue that cannot be read) is left alone, because a job the user started in the
 * browser must never be killed by our Stop.
 */
internal fun AssistantService.stopComfyGeneration() {
    val job = comfyGenJob
    val promptId = comfyGenPromptId
    val config = comfyGenConfig
    comfyGenJob = null
    comfyGenPromptId = null
    comfyGenConfig = null
    job?.cancel()
    if (promptId != null && config != null) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                cancelComfyJob(config, promptId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: the local poll is already cancelled, and Stop must not fail noisily.
            }
        }
    }
}

/**
 * One read of `/queue` followed by the matching cancel action, or nothing.
 *
 * Read-only when the answer does not place OUR id in `queue_running` or `queue_pending` — see
 * [stopComfyGeneration] for why that matters. Internal (not private) so a stub test can drive each
 * of the three cases deterministically without racing a real generation.
 */
internal suspend fun AssistantService.cancelComfyJob(config: ServerConfig, promptId: String) {
    val body = comfyJson(config, COMFY_QUEUE_PATH).body
    when (parseComfyQueuePosition(body, promptId)) {
        ComfyQueuePosition.RUNNING ->
            comfyJson(config, COMFY_INTERRUPT_PATH, method = "POST", body = buildComfyInterruptBody(promptId))
        ComfyQueuePosition.PENDING ->
            comfyJson(config, COMFY_QUEUE_PATH, method = "POST", body = buildComfyQueueDeleteBody(promptId))
        ComfyQueuePosition.ABSENT, ComfyQueuePosition.UNKNOWN -> Unit
    }
}

/**
 * Best-effort `POST /free` after an image is published, when the entry opts in.
 *
 * Failures — a 401, an old server without the route, a dropped connection — are ignored on purpose:
 * the image is already saved, and a housekeeping call must not turn into a user-visible error.
 */
private suspend fun AssistantService.freeComfyMemory(config: ServerConfig) {
    try {
        comfyJson(config, COMFY_FREE_PATH, method = "POST", body = buildComfyFreeBody())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Ignored: freeing memory is opportunistic.
    }
}

/**
 * Streams `/view` to a temp file, then renames it into `gen_` storage.
 *
 * Streamed, not slurped: a 25 MB image (the cap) must never have to exist twice in memory, and the
 * size is checked as the bytes arrive so an oversized or endless response is abandoned partway
 * instead of filling the cache directory. The rename is what makes the file appear atomically —
 * every existing consumer (the transcript, deletion, eviction, Regenerate) only ever sees a COMPLETE
 * image — and a failed or cancelled download leaves nothing behind.
 */
private suspend fun AssistantService.downloadComfyImage(
    config: ServerConfig,
    image: ComfyOutputImage
): File = withContext(Dispatchers.IO) {
    val destination = File(cacheDir, "gen_${System.currentTimeMillis()}.png")
    val partial = File(cacheDir, "${destination.name}.part")
    try {
        client.newCall(imageGenViewRequest(config, image).build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw ImageGenAuthException()
            if (!response.isSuccessful) {
                throw Exception(comfyHttpErrorMessage(response.code, response.message))
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.isNotBlank() && !contentType.startsWith("image/")) {
                throw Exception("ComfyUI returned ${contentType.substringBefore(';')} instead of an image.")
            }
            if (response.body.contentLength() > COMFY_MAX_IMAGE_BYTES) {
                throw Exception(COMFY_IMAGE_TOO_LARGE_MESSAGE)
            }
            val source = response.body.source()
            var written = 0L
            FileOutputStream(partial).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == -1) break
                    written += read
                    if (written > COMFY_MAX_IMAGE_BYTES) throw Exception(COMFY_IMAGE_TOO_LARGE_MESSAGE)
                    output.write(buffer, 0, read)
                }
            }
            if (written == 0L) throw Exception(COMFY_NO_IMAGE_MESSAGE)
        }
        if (!partial.renameTo(destination)) throw Exception("Could not store the generated image.")
        destination
    } catch (e: Throwable) {
        partial.delete()
        throw e
    }
}

/**
 * One JSON call to a ComfyUI endpoint, through the shared credential-carrying builder.
 *
 * 401/403 becomes [ImageGenAuthException] here, so a proxy that expires its session mid-poll
 * surfaces the same "rejected the username or password" bubble as a bad password at submit time.
 */
private suspend fun AssistantService.comfyJson(
    config: ServerConfig,
    path: String,
    method: String = "GET",
    body: String? = null
): ComfyHttpResult = withContext(Dispatchers.IO) {
    val builder = imageGenRequestBuilder(config, path)
    if (body == null) {
        builder.get()
    } else {
        builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
    }
    client.newCall(builder.build()).execute().use { response ->
        val text = response.body.string()
        if (response.code == 401 || response.code == 403) throw ImageGenAuthException()
        ComfyHttpResult(response.code, text, response.message)
    }
}
