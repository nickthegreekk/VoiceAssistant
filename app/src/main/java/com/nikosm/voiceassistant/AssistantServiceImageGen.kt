package com.nikosm.voiceassistant

import okhttp3.Request

// ---------------------------------------------------------------------------
// The single image-gen request path.
//
// Every request to an A1111 (image-gen) server — the dedicated image-generator
// persona, the inline "create an image of …" trigger, Regenerate, the "Test
// Connection" probe (GET /sdapi/v1/sd-models) and the passive health re-check —
// must authenticate with the credentials of a *configured* Image Gen entry.
//
// Those credentials live only on the Servers entry, so each flow has to resolve
// the persona's Backend URL back to that entry. `performImageGeneration` used to
// look the entry up only to take its `.url` and then build a bare, credential-less
// request: A1111 started with `--api-auth` answered 401 to the generation while
// "Test Connection" (which passes the full ServerConfig) succeeded. The resolver
// and the header builder below are now the one place that lookup and those
// headers happen, so a generation and the probe can never disagree.
//
// Pure in / pure out (no Android surface): the resolver and builder are pinned by
// the ImageGenResolutionTest JVM tests; the end-to-end authentication proof lives
// in ImageGenAuthInstrumentedTest.
// ---------------------------------------------------------------------------

/** A1111 model list — the Test Connection / health probe endpoint. */
internal const val IMAGE_GEN_SD_MODELS_PATH = "/sdapi/v1/sd-models"

/** A1111 text-to-image endpoint. */
internal const val IMAGE_GEN_TXT2IMG_PATH = "/sdapi/v1/txt2img"

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
 * Resolves the image-gen entry a persona actually means, or null when nothing
 * matches. Mirrors [resolveGatewayConfig] but over the Image Gen list:
 *  - the entry's NAME against the persona's Backend URL (a name-based Backend URL,
 *    which the persona editor's dropdown writes), or
 *  - the normalized entry URL against the normalized Backend URL.
 *
 * A blank Backend URL falls back to the first configured entry (the same default
 * the old lookup used), which still carries its credentials. A non-blank Backend
 * URL that matches no entry resolves to null so the caller fails visibly instead
 * of sending an unauthenticated request.
 */
internal fun resolveImageGenConfig(
    imageGenServers: List<ServerConfig>,
    backendUrl: String
): ServerConfig? {
    val wanted = backendUrl.trim()
    if (wanted.isEmpty()) return imageGenServers.firstOrNull()
    val wantedUrl = normalizeGatewayUrl(wanted)
    return imageGenServers.firstOrNull { cfg ->
        cfg.name == wanted || normalizeGatewayUrl(cfg.url) == wantedUrl
    }
}

/** [resolveImageGenConfig] against this service's live Image Gen list. */
internal fun AssistantService.findImageGenConfig(backendUrl: String): ServerConfig? =
    resolveImageGenConfig(_imageGenBases.value, backendUrl)

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
