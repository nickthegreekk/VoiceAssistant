package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object ColorSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Color", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Color) = encoder.encodeInt(value.toArgb())
    override fun deserialize(decoder: Decoder): Color = Color(decoder.decodeInt())
}

@Serializable
enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

@Serializable
enum class VoiceMode { NONE, SYSTEM_TTS, BUNDLED_ESPEAK, GATEWAY }

@Serializable
enum class AuthType { NONE, BASIC, API_KEY }

@Serializable
data class ChatMessage(
    val role: String,
    val text: String,
    val reasoning: String? = null,
    val isError: Boolean = false,
    val audioFilePath: String? = null,
    val responseTimeMs: Long? = null,
    val imagePath: String? = null,
    val generatedImagePath: String? = null
)

/**
 * One-shot UI signal: the service is about to play [messageIndex] through a
 * NON-chunked engine, so the classic duration-synced reveal must run again for
 * that message as if it had just arrived.
 *
 * Why this exists: replaying an eSpeak/System TTS message used to show the whole
 * text instantly with no visual sync to the replayed audio, while a live first
 * response got the duration-based typewriter reveal. The chunked Gateway path
 * doesn't need this — its karaoke highlight is the dynamic element and the reveal
 * is deliberately pinned to full text there (see the karaoke gate in MainScreen).
 *
 * [token] increments per request so the UI consumes each request exactly once
 * (the same message can legitimately be replayed twice in a row, and a repeated
 * request must re-arm the reveal each time). [messageIndex] is the message's index
 * into AssistantService._messages at request time; the UI honors the request only
 * while that index still addresses the last message, because only the last
 * assistant bubble is reveal-driven (older bubbles always render in full).
 *
 * Transient UI signalling: built per request, never persisted.
 */
data class RevealRestartRequest(val token: Long, val messageIndex: Int)

@Serializable
data class UsageInfo(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val cost: Double = 0.0
)

@Serializable
data class ModelPricing(
    val prompt: Double,
    val completion: Double
)

@Serializable
data class Persona(
    val name: String,
    @Serializable(with = ColorSerializer::class)
    val themeColor: Color,
    val model: String,      // preferred Ollama model
    val systemPrompt: String,
    val isCloud: Boolean = false,
    val providerIcon: String = "O",
    @Serializable(with = ColorSerializer::class)
    val providerColor: Color = Color(0xFF4ADE80), // Defaults to Ollama green
    val isTranslator: Boolean = false,
    val isImageGenerator: Boolean = false,
    val targetLanguage: String = "English",
    val backendUrl: String = "",
    val allowGatewayFailover: Boolean = false, // S2: opt-in — when false, only backendUrl is ever contacted
    val voiceMode: VoiceMode = VoiceMode.SYSTEM_TTS,
    val voiceEngine: String = "kokoro",
    val kokoroVoice: String = "af_heart",
    // LLM Settings
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 2048,
    val numCtx: Int = 8192,
    val enableThinking: Boolean = true,
    val webSearchEnabled: Boolean = false,
    val ragEnabled: Boolean = false,
    /**
     * Ids of the enabled [Skill]s, from the shared library. Reference-by-id, never a copy
     * of the instruction text — the same relationship a persona's `backendUrl` has to the
     * server list. Editing a skill therefore updates every persona that enabled it at once,
     * with no fan-out write.
     *
     * Defaulted, so personas persisted before Skills existed decode unchanged. Ids that no
     * longer resolve (a skill was deleted) are dropped by `AssistantService.removeSkill` and
     * again by the backup restore, so the UI never has to render an enabled-but-missing skill.
     */
    val enabledSkillIds: List<String> = emptyList(),
    // Custom persona artwork. These hold INTERNAL paths inside the app's own
    // filesDir (filesDir/persona_images/...), never raw photo-picker URIs: the
    // picked image is copied + downsampled into app storage at selection time,
    // so it survives the user deleting/moving the original photo and needs no
    // persistent URI permission grant. Both are optional/nullable.
    val iconImageUri: String? = null,        // square-ish avatar shown in ProviderLogo
    val backgroundImageUri: String? = null   // full-screen backdrop behind the UI
)

@Serializable
data class CloudApiSetting(
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val icon: String,
    @Serializable(with = ColorSerializer::class)
    val color: Color,
    val isEditableUrl: Boolean = false
)

@Serializable
data class ServerConfig(
    val name: String,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val authType: AuthType = AuthType.NONE,
    val apiKey: String? = null
) {
    // Backward compatibility: existing entries with username but default authType should use BASIC
    val effectiveAuthType: AuthType
        get() = if (authType == AuthType.NONE && !username.isNullOrBlank()) AuthType.BASIC else authType
}


/**
 * A multi-persona Room: a shared conversation space with its own persisted identity
 * ([id]) and its own transcript, entirely separate from any participant's individual
 * chat history. Participants are existing personas (see [Room.eligibleMembers]), each of
 * which keeps its own system prompt and its own backend resolution — a Room changes
 * WHO speaks and WHAT they can see, never WHERE a persona's request is routed.
 *
 * [id] is a UUID (not the display name, as for personas) so a room can be renamed freely
 * and so a deleted-and-recreated room can never inherit the old transcript. Membership
 * [memberNames] IS name-keyed, matching the app's name-based persona identity everywhere
 * (history storage, rename migration, backup), and is kept in sync by
 * `AssistantService.updatePersona`/`removePersona`.
 */
/**
 * One entry of the shared Skills library: instruction text plus the trigger phrases that
 * activate it.
 *
 * A skill is defined ONCE and referenced by any number of personas
 * ([Persona.enabledSkillIds]) — the same "defined once, attached to many" relationship the
 * server lists already have to personas, and never duplicated per persona. Editing a skill
 * therefore changes it for every persona that enabled it.
 *
 * When a user's message matches one of this skill's [triggerPhrases] and the speaking persona
 * has the skill enabled, [instructions] is appended to the system prompt for THAT ONE TURN
 * only: it is never persisted into history and never reappears when earlier turns are replayed.
 *
 * [id] is a UUID (not the name) for the same reason [Room]'s is — see its KDoc: a skill must
 * stay freely renameable, and a deleted-then-recreated skill must never inherit the references
 * of the one it replaced.
 */
@Serializable
data class Skill(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    /**
     * Zero or more. Matched case-insensitively as a PREFIX of the trimmed user message,
     * mirroring `isImageGenerationRequest`. A skill with none can still be enabled, but never
     * fires — the UI should say so rather than silently accept a dead skill.
     */
    val triggerPhrases: List<String> = emptyList(),
    /** The text injected into the system prompt on a matching turn. */
    val instructions: String = ""
)

@Serializable
data class Room(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    /** Ordered participants. Order is speaking order in the initial round. */
    val memberNames: List<String> = emptyList(),
    /**
     * Cap on TOTAL autonomous (persona-to-persona) messages in one round, summed across
     * every participant — not rounds, and not per participant. `null` means "inherit the
     * global default" so changing the global setting later updates existing rooms.
     *
     * `0` is a real value meaning "initial round only, no autonomous continuation"; it is
     * never treated as unset. See [RoomReplyPolicy.capFor].
     */
    val autoReplyCap: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Rooms require a model, so a room needs at least two members to be meaningful.
         * Enforced in the editor, not here — this only drives the empty/disabled UI.
         */
        const val MIN_MEMBERS = 2
    }
}

/**
 * One message in a Room transcript. Deliberately its own class rather than a [ChatMessage]
 * with a speaker field: a room transcript needs explicit speaker identity, and a [ChatMessage]
 * carries single-conversation concepts (cached TTS audio, generated images, the binary
 * user/assistant `role` that is fed verbatim to the model) that do not apply here.
 */
@Serializable
data class RoomMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** `user` for the human, or a participant's [Persona.name] for a persona turn. */
    val speakerName: String,
    val text: String,
    val reasoning: String? = null,
    val isError: Boolean = false,
    val responseTimeMs: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** The human's own turn. */
    val isUser: Boolean get() = speakerName == USER_SPEAKER

    companion object {
        /** Reserved [speakerName] for the human — cannot collide with a persona name. */
        const val USER_SPEAKER = "__user__"
    }
}

// Result of a setup-context connection test (Add/Edit Server dialogs, "Test Connection",
// status-dot re-check). Structured so the setup UI can map the raw outcome to a specific,
// actionable message — unlike the passive status dot, which intentionally stays coarse.
data class ServerConnectionResult(
    val success: Boolean,
    val httpCode: Int? = null,  // HTTP status when the server responded but rejected the request
    val detail: String? = null  // raw detail: HTTP status phrase or exception message
)

/**
 * Can [persona] take part in a Room at all?
 *
 * This mirrors the real routing branches in `sendTextMessageToServer` rather than
 * approximating them, because a persona that cannot be routed conversationally must never
 * be offered as a room member — the turn would either corrupt the single-chat transcript or
 * silently bypass the room. Each exclusion below is a HARD requirement, not a warning:
 *
 *  - [Persona.isImageGenerator]: `sendTextMessageToServer` short-circuits to
 *    `performImageGeneration` BEFORE any conversational routing, and that function still
 *    writes `_messages` directly. A room turn would drop the generated image into the
 *    single-chat transcript. (Carried forward from Phase 0 as a requirement.)
 *  - [Persona.isTranslator]: the Ollama/cloud request builders REPLACE both the system
 *    prompt and the entire history with a single "translate this" user message, so a
 *    translator cannot see — let alone participate in — a multi-party transcript.
 *  - Gateway-only personas (no direct Ollama server and no cloud provider): the gateway
 *    `/text-chat` path sends neither the system prompt nor any history, so such a persona
 *    would reply blind, with no idea who it is talking to. Excluded in v1 by decision.
 *
 * Cloud personas ARE eligible: they simply never take part in autonomous continuation
 * (see [RoomReplyPolicy.participatesInAutonomousRounds]).
 */
fun Persona.roomEligibility(ollamaServerNames: Set<String>, cloudProviderNames: Set<String>): RoomEligibility {
    if (isImageGenerator) return RoomEligibility.Blocked("Image generators can't converse in a room")
    if (isTranslator) return RoomEligibility.Blocked("Translators replace the conversation with a single translation request")
    if (model.isBlank()) return RoomEligibility.Blocked("No model selected")

    // Parse the same "[Provider] model" / "[Server] model" form the router parses.
    // The trim() matters: the real isCloudModel() trims before testing the bracket form, so
    // an untrimmed model routes to cloud while an untrimmed parse here would not.
    val m = model.trim()
    val displayServer: String? = if (m.startsWith("[") && m.contains("] ")) {
        m.substring(1, m.indexOf("]"))
    } else {
        null
    }

    // Exactly `persona.isCloud && isCloudModel(model)`, decomposed against the configured
    // provider names (named routesToCloud, not `isCloud`, to avoid shadowing the Persona
    // property the left-hand side reads).
    val routesToCloud = isCloud && displayServer != null && displayServer in cloudProviderNames
    // Direct Ollama: a "[Server]" prefix naming a CONFIGURED Ollama server, exactly the
    // `_ollamaBaseUrls.find { it.name == displayServer }` test the router performs.
    val isDirectOllama = displayServer != null && displayServer in ollamaServerNames

    return when {
        routesToCloud -> RoomEligibility.Eligible(cloud = true)
        isDirectOllama -> RoomEligibility.Eligible(cloud = false)
        // A bare model name (no bracket prefix) reaches Ollama via the gateway's
        // backend_url/failover path, which is history-blind — same exclusion.
        else -> RoomEligibility.Blocked("Only reachable through the gateway, which can't carry room history")
    }
}

/** Result of [roomEligibility]. */
sealed class RoomEligibility {
    data class Eligible(val cloud: Boolean) : RoomEligibility()
    data class Blocked(val reason: String) : RoomEligibility()
}

/**
 * How far a Room round may run on its own.
 *
 * The cap counts TOTAL autonomous messages across ALL participants (not rounds, not
 * per-participant) — so a 3-persona room with a cap of 4 stops after 4 persona turns
 * total, however they are distributed. [Room.autoReplyCap] overrides [globalCap] per
 * room; the cap is a hard ceiling and the round can also be cut short at any point by
 * the user pressing Stop (enforced in the turn loop, not here).
 */
object RoomReplyPolicy {
    /**
     * Default cap on autonomous messages per round. Deliberately tight: personas talking
     * to each other is a novelty that degrades fast without supervision, and every
     * autonomous message on a cloud persona costs real money.
     */
    const val DEFAULT_AUTO_REPLY_CAP = 4

    /**
     * The effective cap for [room].
     *
     * 0 is a REAL cap, not an "unset" marker: `?:` only falls back on `null`, so a room
     * explicitly set to 0 resolves to 0 and the turn loop's `while (emitted < cap)` never
     * enters — the initial round still runs in full, and only the self-started
     * continuation is suppressed. "Inherit the default" is expressed by `null`, never by
     * 0, and the editor's parser keeps those two apart via ROOM_CAP_INHERIT.
     */
    fun capFor(room: Room): Int = room.autoReplyCap ?: DEFAULT_AUTO_REPLY_CAP

    /**
     * Q1: cloud personas take part in the INITIAL round (the user asked, everyone answers)
     * but are EXCLUDED from autonomous continuation entirely. They therefore contribute
     * exactly one message per user turn, and never consume autonomous budget.
     */
    fun participatesInAutonomousRounds(cloud: Boolean): Boolean = !cloud
}

/** Current phase of a Room round, for UI + the turn loop. */
enum class RoomRoundPhase { IDLE, USER_TURN, INITIAL_ROUND, AUTONOMOUS, STOPPING }

/** Observable round state. Null [activeRoom] means no round is in progress. */
data class RoomRoundState(
    val roomId: String,
    val phase: RoomRoundPhase = RoomRoundPhase.USER_TURN,
    /** Index into the room's member list of the persona whose turn is running. */
    val currentMemberIndex: Int = 0,
    /** Autonomous messages emitted so far this round (never exceeds the cap). */
    val autonomousCount: Int = 0,
    val cap: Int = RoomReplyPolicy.DEFAULT_AUTO_REPLY_CAP,
    /** Set by Stop; the loop checks it between every participant turn. */
    val stopRequested: Boolean = false
)

/** Personas eligible to join [room], in the order the caller supplied them. */
fun eligibleRoomMembers(
    personas: List<Persona>,
    ollamaServerNames: Set<String>,
    cloudProviderNames: Set<String>
): List<Persona> = personas.filter {
    it.roomEligibility(ollamaServerNames, cloudProviderNames) is RoomEligibility.Eligible
}


data class CertApprovalRequest(
    val host: String,
    val fingerprint: String,
    val certificate: java.security.cert.X509Certificate
)

val DEFAULT_PERSONAS = listOf(
    Persona(
        name = "Assistant",
        themeColor = Color(0xFF4ADE80), // green
        model = "",
        systemPrompt = "You are a helpful, concise assistant.",
        isCloud = false,
        providerIcon = "O",
        providerColor = Color(0xFF4ADE80),
        isTranslator = false
    ),
    Persona(
        name = "Friend",
        themeColor = Color(0xFFD97706), // amber / earth tone
        model = "",
        systemPrompt = "You are a good friend. Keep responses conversational and warm.",
        isCloud = false,
        providerIcon = "O",
        providerColor = Color(0xFF4ADE80),
        isTranslator = false
    )
)

val CLOUD_PERSONAS = listOf(
    Persona(
        name = "Claude",
        themeColor = Color(0xFFD97755),
        model = "[Anthropic] claude-3-5-sonnet-latest",
        systemPrompt = "You are Claude, a helpful and honest assistant by Anthropic.",
        isCloud = true,
        providerIcon = "A",
        providerColor = Color(0xFFD97755),
        isTranslator = false
    ),
    Persona(
        name = "GPT-4o",
        themeColor = Color(0xFF10A37F),
        model = "[OpenAI] gpt-4o",
        systemPrompt = "You are GPT-4o, a large language model trained by OpenAI.",
        isCloud = true,
        providerIcon = "O",
        providerColor = Color(0xFF10A37F),
        isTranslator = false
    ),
    Persona(
        name = "DeepSeek",
        themeColor = Color(0xFF4D6BFE),
        model = "[DeepSeek] deepseek-chat",
        systemPrompt = "You are DeepSeek, a helpful AI assistant.",
        isCloud = true,
        providerIcon = "D",
        providerColor = Color(0xFF4D6BFE),
        isTranslator = false
    ),
    Persona(
        name = "Gemini",
        themeColor = Color(0xFF4285F4),
        model = "[Google] gemini-1.5-pro",
        systemPrompt = "You are Gemini, a generative AI from Google.",
        isCloud = true,
        providerIcon = "G",
        providerColor = Color(0xFF4285F4),
        isTranslator = false
    ),
    Persona(
        name = "Cline",
        themeColor = Color(0xFF6366F1),
        model = "[Cline] anthropic/claude-sonnet-4-6",
        systemPrompt = "You are a helpful AI assistant powered by Cline.",
        isCloud = true,
        providerIcon = "CL",
        providerColor = Color(0xFF6366F1),
        isTranslator = false
    ),
    Persona(
        name = "Custom LLM",
        themeColor = Color(0xFF808080),
        model = "[OpenAI-Compatible] llama3",
        systemPrompt = "You are an AI assistant.",
        isCloud = true,
        providerIcon = "C",
        providerColor = Color(0xFF808080),
        isTranslator = false
    )
)

val TRANSLATOR_PERSONA = Persona(
    name = "Translator",
    themeColor = Color.Cyan,
    model = "[Ollama] translategemma:latest",
    systemPrompt = "Translate the input precisely.",
    isCloud = false,
    providerIcon = "T",
    providerColor = Color.Cyan,
    isTranslator = true
)

val DEFAULT_CLOUD_APIS = listOf(
    CloudApiSetting("Anthropic", "https://api.anthropic.com", "", "A", Color(0xFFD97755)),
    CloudApiSetting("OpenAI", "https://api.openai.com/v1", "", "O", Color(0xFF10A37F)),
    CloudApiSetting("DeepSeek", "https://api.deepseek.com/v1", "", "D", Color(0xFF4D6BFE)),
    CloudApiSetting("Google", "https://generativelanguage.googleapis.com", "", "G", Color(0xFF4285F4)),
    CloudApiSetting("Cline", "https://api.cline.bot/api/v1", "", "CL", Color(0xFF6366F1)),
    CloudApiSetting("OpenAI-Compatible", "http://0.0.0.0:11434/v1", "", "C", Color(0xFF808080), isEditableUrl = true)
)

// Cline Bot does not expose a /models endpoint — model fetching for this provider
// must use this static list instead of hitting the API. Model IDs follow the
// provider/model-name convention documented at docs.cline.bot/api/models.
// Free models (no cost) are listed first.
val CLINE_BOT_MODELS = listOf(
    // === FREE (no credit usage) ===
    "minimax/minimax-m2.5",
    "minimax/minimax-m2.7",

    // === Anthropic (Claude) ===
    "anthropic/claude-sonnet-4-6",
    "anthropic/claude-3-5-sonnet-latest",
    "anthropic/claude-3-5-haiku-latest",
    "anthropic/claude-3-opus-latest",

    // === OpenAI ===
    "openai/gpt-4o",
    "openai/gpt-4o-mini",
    "openai/gpt-4-turbo",
    "openai/gpt-3.5-turbo",
    "openai/o1-preview",
    "openai/o1-mini",

    // === Google (Gemini) ===
    "google/gemini-2.5-pro",
    "google/gemini-2.0-flash",
    "google/gemini-1.5-pro",
    "google/gemini-1.5-flash",

    // === DeepSeek ===
    "deepseek/deepseek-chat",
    "deepseek/deepseek-reasoner",
    "deepseek/deepseek-coder",

    // === xAI (Grok) ===
    "xai/grok-3",
    "xai/grok-3-mini"
)

val TRANSLATION_LANGUAGES = listOf(
    "English", "Chinese", "Spanish", "French", "German", 
    "Japanese", "Portuguese", "Russian", "Italian", "Korean",
    "Hebrew", "Greek", "Dutch", "Turkish", "Arabic", "Hindi"
)

val PERSONA_PALETTE = listOf(
    Color(0xFF4ADE80), // Ollama Green
    Color(0xFFD97706), // Amber
    Color(0xFF4285F4), // Google Blue
    Color(0xFF10A37F), // OpenAI Green
    Color(0xFFD97755), // Anthropic Clay
    Color(0xFF4D6BFE), // DeepSeek Blue
    Color(0xFF8B5CF6), // Purple
    Color(0xFFEC4899), // Pink
    Color(0xFFEF4444), // Red
    Color(0xFF06B6D4), // Cyan
    Color(0xFF808080)  // Gray
)
