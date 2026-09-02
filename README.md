# VoiceAssistant

A privacy-focused, open-source Android voice assistant that connects to multiple LLM providers. Features hands-free voice interaction, on-device voice activity detection, and support for both local (Ollama) and cloud-based AI models.

## Features

### Voice Interaction
- **Hands-free mode** with on-device Voice Activity Detection (Silero VAD) — detects when you start and stop speaking, entirely on your phone
- **Speech recognition** via Whisper, running on your self-hosted Voice Gateway server (see [Voice Gateway Setup](#voice-gateway-tts--stt-server))
- **Text-to-Speech (TTS)** — choose per persona: bundled eSpeak-NG (fully offline, no server needed), Android's system TTS, or high-quality Kokoro TTS via your self-hosted Gateway
- **Automatic language detection for TTS routing** — a lightweight, fully on-device Unicode-script heuristic (no cloud APIs, no telemetry) prevents non-Latin-script text (Greek, Russian, Hebrew, Arabic, Chinese, Japanese, Korean, etc.) from being mispronounced letter-by-letter by an English voice

### LLM Providers
Connect to your choice of AI backend:

- **Ollama** - Run models locally on your own hardware
- **Anthropic** - Claude models
- **OpenAI** - GPT models
- **DeepSeek** - DeepSeek chat models
- **Google** - Gemini models
- **OpenAI-Compatible** - Any OpenAI API-compatible server

### AI Personas
Pre-configured personas for different use cases:
- **Assistant** - General helpful assistant
- **Friend** - Conversational and warm personality
- **Claude, GPT-4o, DeepSeek, Gemini** - Cloud provider personas
- **Translator** - Multi-language translation support (16 languages)
- **Custom LLM** - Connect to any OpenAI-compatible server

### Advanced Features
- **RAG (Retrieval Augmented Generation)** - Connect to a knowledge base for document-aware responses
- **Web Search** - SearXNG integration for real-time web queries
- **File Attachments** - Attach documents to your messages
- **Conversation History** - Persistent chat history per persona
- **Usage Tracking** - Token usage and cost monitoring
- **Thinking Mode** - Support for reasoning models (e.g., DeepSeek V4-Pro's built-in Thinking mode) with a collapsible "Thought process" view, separate from the final answer

### Privacy & Security
- **No telemetry** - Removed ML Kit dependency to prevent Firebase data collection
- **On-device language detection** - No cloud-based language identification
- **Encrypted preferences** - Settings stored with Android EncryptedSharedPreferences
- **Certificate pinning** - TOFU (Trust On First Use) with user approval for self-signed certs
- **No account required** - Direct connection to your chosen LLM provider

## Requirements

- Android 12+ (API 31+)
- ARM64 device
- Microphone permission
- Internet connection (for cloud providers)

## Installation

1. Download the latest APK from the [Releases](../../releases) page
2. Install on your Android device
3. Grant microphone permission when prompted
4. Configure your preferred LLM provider in Settings

## Setup

### Voice Gateway (TTS + STT server)

Voice features (Kokoro TTS, Whisper speech recognition, and real-time translation) require a self-hosted Voice Gateway — a small Python server you run on your own hardware. This is entirely optional: personas can also use bundled eSpeak-NG or your phone's system TTS with no server at all, and native Ollama personas work with text only, no gateway required.

Full setup instructions (NVIDIA GPU driver install, LXC/container GPU passthrough, dependency installation, and the systemd service) are in **[SERVER_SETUP.md](SERVER_SETUP.md)**.

Once running, add your Gateway's URL in Settings → Servers, and select it as the Voice via option for any persona.

### Ollama (Local)
1. Install Ollama on your server
2. Pull your desired model: `ollama pull llama3`
3. In the app, add your Ollama server URL (e.g., `http://192.168.1.100:11434`)
4. Select a persona and start chatting

### Cloud Provider
1. Go to Settings → Cloud APIs
2. Enter your API key for your preferred provider
3. The app will automatically fetch available models
4. Select a model and start chatting

### RAG (Optional)
1. Set up a RAG server with your knowledge base
2. In Settings, configure:
    - RAG Server URL
    - Username and Password
3. Enable RAG in the persona settings

### Web Search (Optional)
1. Set up a SearXNG instance
2. In Settings, enter your SearXNG URL
3. Enable web search in the persona settings

## Architecture

- **Kotlin + Jetpack Compose** - Modern Android UI
- **ONNX Runtime** - On-device ML inference (Silero VAD)
- **eSpeak-NG** - Bundled TTS engine (GPL v3)
- **OkHttp** - HTTP client for API communication
- **Kotlinx Serialization** - JSON serialization

## License

This project is licensed under the **GNU General Public License v3.0** (GPL v3).

See [LICENSE](LICENSE) for the full license text.

The GPL v3 license ensures that this software remains free and open source. You are free to:
- Use the software for any purpose
- Study how it works and modify it
- Distribute copies
- Distribute modified versions

**Note:** This project bundles eSpeak-NG, which is licensed under GPL v3. This requires the entire project to be distributed under GPL v3 terms.

## Contributing

Contributions are welcome! Please feel free to:
- Report bugs
- Suggest features
- Submit pull requests
- Improve documentation

## Acknowledgments

- [eSpeak-NG](https://github.com/espeak-ng/espeak-ng) - Text-to-speech engine
- [Silero VAD](https://github.com/snakers4/silero-vad) - Voice Activity Detection
- [Ollama](https://ollama.ai/) - Local LLM runtime
- [ONNX Runtime](https://onnxruntime.ai/) - ML inference framework

## Disclaimer

This app connects to third-party AI services. You are responsible for:
- Managing your API keys securely
- Complying with the terms of service of your chosen LLM provider
- Any costs incurred from API usage

The developers of this app are not responsible for any data shared with third-party services.


  ### App screenshot
<img width="600" height="800" alt="Screenshot_20260902-102433_Celeste" src="https://github.com/user-attachments/assets/9ea57b8f-f966-499a-af5e-77e59ef3c41a" />


