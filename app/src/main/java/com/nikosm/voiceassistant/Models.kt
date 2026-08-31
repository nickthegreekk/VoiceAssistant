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
data class ChatMessage(
    val role: String,
    val text: String,
    val reasoning: String? = null,
    val isError: Boolean = false,
    val audioFilePath: String? = null,
    val responseTimeMs: Long? = null
)

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
    val enableThinking: Boolean = true,
    val webSearchEnabled: Boolean = false
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
    val password: String? = null
)

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
    CloudApiSetting("OpenAI-Compatible", "http://0.0.0.0:11434/v1", "", "C", Color(0xFF808080), isEditableUrl = true)
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
