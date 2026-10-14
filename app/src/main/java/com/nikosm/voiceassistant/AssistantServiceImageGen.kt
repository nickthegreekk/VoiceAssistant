package com.nikosm.voiceassistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File

// ---------------------------------------------------------------------------
// The single image-gen request path.
//
// Every request to an image server — the dedicated image-generator persona, the inline
// "create an image of …" trigger, Regenerate, the Test Connection probe and the passive health
// re-check — is built by the functions below, and authenticates with the credentials of a
// *configured* Image Gen entry. Those credentials live only on the Servers entry, so each flow has
// to resolve a request back to that entry.
//
// Which entry that is, in order:
//   1. the persona's own pin, when it still matches a configured entry, or
//   2. the FIRST entry of the ACTIVE backend the user picked in Settings.
//
// A pin that no longer matches never fails the request: it degrades to the active generator and is
// reported as stale so the persona editor can say so. Only "the active backend has no entry at all"
// stops a generation, and that error names the type the user needs to add.
//
// Pure in / pure out (no Android surface): the resolver, the pin rule and the builder are pinned by
// the ImageGenResolutionTest and ImageGenActiveBackendTest JVM tests; the end-to-end authentication
// proof lives in ImageGenAuthInstrumentedTest, and the switch between backends in
// ComfyImageInstrumentedTest.
// ---------------------------------------------------------------------------

/** A1111 model list — the Test Connection / health probe endpoint. */
internal const val IMAGE_GEN_SD_MODELS_PATH = "/sdapi/v1/sd-models"

/** A1111 text-to-image endpoint. */
internal const val IMAGE_GEN_TXT2IMG_PATH = "/sdapi/v1/txt2img"

/**
 * ComfyUI's Test Connection / health probe endpoint.
 *
 * Chosen over its neighbours deliberately: `GET /prompt` returns queue state and `/queue` the
 * full queue, so both are heavier and semantically about work rather than liveness; `/models`
 * and `/object_info` can be large. `/system_stats` is a small JSON 200 with no side effects and
 * answers even when no model is loaded — which is exactly what a cold server looks like.
 */
internal const val COMFY_SYSTEM_STATS_PATH = "/system_stats"

/** ComfyUI: queue a workflow. Body `{"prompt": <api workflow>, "client_id": <uuid>}`. */
internal const val COMFY_PROMPT_PATH = "/prompt"

/** ComfyUI: poll a queued/running/finished job. `GET /history/{prompt_id}`. */
internal const val COMFY_HISTORY_PATH = "/history"

/** ComfyUI: fetch one produced file. `GET /view?filename=…&subfolder=…&type=…`. */
internal const val COMFY_VIEW_PATH = "/view"

/** ComfyUI: stop the currently running workflow (global, not per-prompt). */
internal const val COMFY_INTERRUPT_PATH = "/interrupt"

/** ComfyUI: queue state — `{"queue_running": [...], "queue_pending": [...]}`. */
internal const val COMFY_QUEUE_PATH = "/queue"

/**
 * ComfyUI: free VRAM/model memory. Body `{"unload_models": bool, "free_memory": bool}`; both
 * default false server-side (verified in ComfyUI's `server.py`, `post_free`). Best-effort only.
 */
internal const val COMFY_FREE_PATH = "/free"

/**
 * The negative prompt Celeste supplies to `%NEGATIVE%` in a ComfyUI template — the same literal
 * A1111 has always been sent. One constant so the two backends cannot drift.
 */
internal const val IMAGE_GEN_NEGATIVE_PROMPT =
    "blurry, distorted, low quality, extra limbs, deformed"

/**
 * The A1111 text-to-image body, exactly as it has always been built.
 *
 * Extracted so the payload-capture test can assert the bytes the app sends without standing up a
 * server — the regression this feature must not cause is a silently changed A1111 request. The
 * call site still posts this string verbatim.
 */
internal fun buildA1111Txt2ImgBody(prompt: String): String = org.json.JSONObject().apply {
    put("prompt", prompt)
    put("negative_prompt", IMAGE_GEN_NEGATIVE_PROMPT)
    put("steps", 20)
    put("width", 512)
    put("height", 512)
}.toString()

/**
 * The endpoint Test connection and the passive health re-check probe, per backend.
 *
 * Both go through [imageGenRequestBuilder], so the probe carries exactly the credentials a
 * generation does — the 401 fix this file exists to preserve.
 */
internal fun imageGenProbePath(backendType: ImageGenBackend): String = when (backendType) {
    ImageGenBackend.A1111 -> IMAGE_GEN_SD_MODELS_PATH
    ImageGenBackend.COMFYUI -> COMFY_SYSTEM_STATS_PATH
}

/**
 * The error bubble shown when the image server rejects the request with 401/403 —
 * a specific, actionable message rather than the raw status code.
 */
internal const val IMAGE_GEN_AUTH_FAILURE_MESSAGE =
    "The image server rejected the username or password. Check them in Settings → Servers → Image Gen."

/**
 * Marker thrown when the image server answers 401/403, so the generation catch can
 * show [IMAGE_GEN_AUTH_FAILURE_MESSAGE] instead of a bare "HTTP 401".
 */
internal class ImageGenAuthException : Exception(IMAGE_GEN_AUTH_FAILURE_MESSAGE)

/**
 * Marker thrown when the address answered the OTHER backend's own probe, so the generation catch can
 * show [imageGenBackendMismatchMessage] verbatim.
 *
 * The diagnosis is already the complete instruction ("looks like ComfyUI; add it under ComfyUI
 * servers"), so wrapping it in the generic "Image generation error:" line only buries it. Distinct
 * from [ImageGenAuthException] because that one has its own fixed, credential-specific wording.
 */
internal class ImageGenBackendMismatchException(val diagnosis: String) : Exception(diagnosis)

/**
 * Does this status mean "a running server that has no such route"?
 *
 * 404 is an unknown path; 405 is a known path that does not take this method. They are the only two
 * statuses that make "is this the wrong backend?" worth ASKING about — and asking is all this
 * decides. The claim is only made once the other backend's own probe actually answers (see
 * [imageGenBackendMismatchHint]). 401/403 keep their own message, because a rejected credential
 * says nothing about which API is behind the address.
 */
internal fun isImageGenBackendMismatchStatus(code: Int): Boolean = code == 404 || code == 405

/**
 * The OTHER backend's probe path — the request that decides a mismatch.
 *
 * Deliberately the same endpoint Test connection uses for that type ([imageGenProbePath]), so
 * "the address answers ComfyUI's probe" means exactly "it would pass Test connection as ComfyUI".
 */
internal fun oppositeImageGenProbePath(backendType: ImageGenBackend): String = when (backendType) {
    ImageGenBackend.A1111 -> COMFY_SYSTEM_STATS_PATH
    ImageGenBackend.COMFYUI -> IMAGE_GEN_SD_MODELS_PATH
}

/**
 * The message shown when an entry's address turns out to answer the OTHER backend.
 *
 * Names the detected API and then the section to move the entry to, because the fix is a move: the
 * address and the credentials are already right, only the type is wrong.
 */
internal fun imageGenBackendMismatchMessage(from: ImageGenBackend): String {
    val to = if (from == ImageGenBackend.A1111) ImageGenBackend.COMFYUI else ImageGenBackend.A1111
    return "This address looks like ${to.displayName}. " +
        "Add it under ${to.displayName} servers instead of ${from.displayName}."
}

/**
 * Asks the other backend's probe endpoint on the same address, with the same credentials.
 *
 * Returns the backend that answered, or null when nothing did. A null is the common case and must
 * stay cheap: unreachable host, timeout, TLS refusal, or a credential the other API rejects all mean
 * "no evidence", never "the other backend". Cancellation is rethrown — this runs inside generation,
 * which the Stop path cancels.
 */
internal suspend fun AssistantService.probeOppositeImageGenBackend(config: ServerConfig): ImageGenBackend? {
    val other = if (config.backendType == ImageGenBackend.A1111) ImageGenBackend.COMFYUI else ImageGenBackend.A1111
    return withContext(Dispatchers.IO) {
        try {
            client.newCall(imageGenRequestBuilder(config, imageGenProbePath(other)).build())
                .execute().use { if (it.isSuccessful) other else null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * The "wrong section" hint for a [code] answer from [config]'s own endpoint, or null.
 *
 * Called only after the backend's real request already failed for a status that cannot be its API,
 * so the extra probe costs nothing on the happy path.
 */
internal suspend fun AssistantService.imageGenBackendMismatchHint(config: ServerConfig, code: Int): String? {
    if (!isImageGenBackendMismatchStatus(code)) return null
    if (probeOppositeImageGenBackend(config) == null) return null
    return imageGenBackendMismatchMessage(config.backendType)
}

/**
 * What an image request resolved to.
 *
 * A result type rather than a nullable `ServerConfig`: "no entry" is not one condition, and the two
 * the app can be in need different words. [NoEntryForActiveBackend] is a SETUP problem the user can
 * fix, and naming the type is what makes it fixable.
 */
internal sealed interface ImageGenResolution {
    /**
     * [config] is the entry to use.
     *
     * [stalePinnedUrl] is the persona's own pin when it matched no entry and the active generator
     * was used instead. The persona editor shows it as a note, rather than letting the user believe
     * a deleted or re-addressed server is still doing the work.
     */
    data class Resolved(val config: ServerConfig, val stalePinnedUrl: String? = null) : ImageGenResolution

    /** The active backend has no configured entry at all. */
    data class NoEntryForActiveBackend(val backend: ImageGenBackend) : ImageGenResolution
}

/**
 * Which entry an image request should use.
 *
 * Resolution order:
 *  1. [pinnedUrl], when it names or addresses a configured entry. A persona that pinned a server
 *     keeps using it, whichever type that entry is.
 *  2. The FIRST entry of [activeBackend] — the generator the user chose in Settings. So a pin that
 *     no longer matches (its entry was re-addressed, renamed, replaced or deleted) degrades to the
 *     configured generator instead of failing: "the server I pinned is gone" is a reason to use the
 *     active one, not to refuse to generate.
 *  3. Nothing — reported as [ImageGenResolution.NoEntryForActiveBackend], so the caller can name the
 *     type the user actually needs to add.
 *
 * "First" means the user's own ordering, which the Image Gen screen exposes with move-up/down.
 */
internal fun resolveImageGenConfig(
    imageGenServers: List<ServerConfig>,
    activeBackend: ImageGenBackend,
    pinnedUrl: String?
): ImageGenResolution {
    val pin = pinnedUrl?.trim().orEmpty()
    if (pin.isNotEmpty()) {
        val pinUrl = normalizeGatewayUrl(pin)
        imageGenServers.firstOrNull { it.name == pin || normalizeGatewayUrl(it.url) == pinUrl }
            ?.let { return ImageGenResolution.Resolved(it) }
    }
    val active = imageGenServers.firstOrNull { it.backendType == activeBackend }
        ?: return ImageGenResolution.NoEntryForActiveBackend(activeBackend)
    // A non-blank pin that matched nothing is STALE — the caller shows that rather than pretending
    // the persona's own server is still in use.
    return ImageGenResolution.Resolved(active, stalePinnedUrl = pin.ifEmpty { null })
}

/** [resolveImageGenConfig] against this service's live Image Gen list and active backend. */
internal fun AssistantService.findImageGenResolution(pinnedUrl: String?): ImageGenResolution =
    resolveImageGenConfig(_imageGenBases.value, _activeImageGenBackend.value, pinnedUrl)

/**
 * Which ComfyUI workflow a generation submits: the persona's chosen **Image Character** when it
 * resolves, else the server entry's own template.
 *
 * Persona-first, because a character is a property of WHO is speaking — two personas on the SAME
 * ComfyUI server must be able to paint different faces — while the entry's workflow stays the
 * shared default for an inline "create an image of …" from a persona that never picked one.
 *
 * A dangling id (its library entry was deleted) and a blank template both fall through to the
 * entry, never to an error: the same "a stale pin degrades rather than fails" rule
 * [resolveImageGenConfig] follows. Returns null only when neither source has a workflow, which the
 * caller reports as [COMFY_NO_WORKFLOW_MESSAGE].
 *
 * Pure, so the precedence is unit-tested without a service or a device.
 */
internal fun resolveImageWorkflow(
    personaWorkflowId: String?,
    library: List<ImageWorkflow>,
    entryWorkflow: String?
): String? {
    val fromPersona = personaWorkflowId
        ?.let { id -> library.firstOrNull { it.id == id }?.workflow }
        ?.takeIf { it.isNotBlank() }
    return fromPersona ?: entryWorkflow?.takeIf { it.isNotBlank() }
}

/**
 * A library name that cannot collide: [base], or "base 2", "base 3", … when taken.
 *
 * Importing two exports of the same character is normal (a re-export after tweaking a node), and
 * silently reusing the first name would make two library rows indistinguishable in the persona
 * editor's dropdown. A blank base becomes "Character" so a nameless file still gets a label.
 */
internal fun uniqueImageWorkflowName(base: String, existing: List<ImageWorkflow>): String {
    val trimmed = base.trim().ifBlank { "Character" }
    if (existing.none { it.name == trimmed }) return trimmed
    var n = 2
    while (existing.any { it.name == "$trimmed $n" }) n++
    return "$trimmed $n"
}

/**
 * The URL a persona pins its image server with, or null to follow the active generator.
 *
 * ONLY an image-generator persona may pin. Its Backend URL was picked from the Image Gen list, so
 * it means an image server. Any other persona's Backend URL is where its CHAT goes — an Ollama
 * base, a gateway, a cloud provider — and feeding that to the image resolver is exactly what
 * produced "No image server is configured for this persona" for every inline "create an image of …"
 * from a normal persona. The inline trigger follows the active generator instead.
 */
internal fun imageGenPinnedUrl(persona: Persona): String? =
    if (persona.isImageGenerator) persona.backendUrl else null

/**
 * The error shown when the ACTIVE image backend has no entry.
 *
 * Names the type and where to add one. It deliberately does not blame the persona: the persona was
 * never the problem, and "not configured for this persona" sent the user looking in the wrong place.
 */
internal fun noEntryForActiveImageBackendMessage(backend: ImageGenBackend): String =
    "No ${backend.displayName} server is configured. Add one in Settings → Servers → Image Gen, " +
        "under ${backend.displayName}."

/**
 * The one place image-gen request headers are built: normalizes the entry's URL,
 * appends [path], and attaches the entry's auth header (Basic or API key) through
 * the shared [suggestedRepliesAuthHeader] / `effectiveAuthType` handling. Every
 * image-gen request is built here, so none can go out bare while Test connection
 * authenticates.
 */
internal fun imageGenRequestBuilder(config: ServerConfig, path: String): Request.Builder {
    val builder = Request.Builder().url(normalizeGatewayUrl(config.url) + path)
    suggestedRepliesAuthHeader(config)?.let { builder.header("Authorization", it) }
    return builder
}

/**
 * `GET /view?filename=…&subfolder=…&type=…` for one produced file.
 *
 * Built through HttpUrl rather than by concatenating a query string, because every value is
 * user-visible text the server produced: a filename can hold spaces, `#`, `%` or non-ASCII, and a
 * `subfolder` legitimately contains `/`. Concatenation would corrupt all of those (and `#` would
 * truncate the URL outright). The Authorization header comes from the same shared helper every
 * other image-gen request uses, so a ComfyUI behind a proxy cannot be reached unauthenticated.
 */
internal fun imageGenViewRequest(config: ServerConfig, image: ComfyOutputImage): Request.Builder {
    val url = (normalizeGatewayUrl(config.url) + COMFY_VIEW_PATH).toHttpUrl().newBuilder()
        .addQueryParameter("filename", image.filename)
        .addQueryParameter("subfolder", image.subfolder)
        .addQueryParameter("type", image.type)
        .build()
    val builder = Request.Builder().url(url)
    suggestedRepliesAuthHeader(config)?.let { builder.header("Authorization", it) }
    return builder
}

/**
 * The one place a generated image becomes a chat message.
 *
 * Both backends finish here, so the `gen_` file convention, the ChatMessage shape and the state
 * transitions cannot drift apart. That is exactly what keeps every existing behaviour working for a
 * ComfyUI image with no changes at all: deletion (`removeMessage`), clear-chat, history eviction,
 * Regenerate (which deletes `generatedImagePath` and re-runs) and save-to-gallery all key off
 * `generatedImagePath` and the `gen_` prefix they already know.
 *
 * [startTime] is the moment the turn began, so the reported time covers the whole job — including
 * ComfyUI's cold model load, not just the download.
 */
internal fun AssistantService.publishGeneratedImage(prompt: String, imageFile: File, startTime: Long) {
    _state.value = AssistantState.IDLE
    updateNotification("Ready to help")
    _messages.value = _messages.value + ChatMessage(
        role = "assistant",
        text = prompt,
        generatedImagePath = imageFile.absolutePath,
        responseTimeMs = System.currentTimeMillis() - startTime
    ).stampedAt()
    saveSettings()
}
