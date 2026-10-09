package com.nikosm.voiceassistant

/**
 * Loads the captured ComfyUI fixtures from `app/src/test/resources/comfyui/`.
 *
 * The captured files are the point of these tests: they pin the parser against what a real ComfyUI
 * actually answers, including the fields nobody would invent. Until a capture is committed, [load]
 * falls back to the caller's stand-in — the same shape, transcribed from the ComfyUI server source
 * (`server.py`, `execution.py`, `main.py`, `nodes.py`) — and says so on stdout, so a suite log
 * always records whether the real capture ran or the stand-in did.
 */
internal object ComfyFixtures {

    private const val NOT_CAPTURED =
        "ComfyFixtures: comfyui/%s is not a usable capture yet — using the verified stand-in shape"

    fun load(name: String, fallback: String): String {
        val captured = ComfyFixtures::class.java.classLoader
            ?.getResourceAsStream("comfyui/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
        // A BLANK file counts as "no capture", not as a capture that happens to be empty. A 0-byte
        // placeholder would otherwise be fed straight to the parser (failing confusingly) while ALSO
        // suppressing the message that says the real capture is missing — the one signal that tells
        // a reader these assertions are still running against a stand-in.
        if (!captured.isNullOrBlank()) return captured
        println(NOT_CAPTURED.format(name))
        return fallback
    }

    /**
     * Loads a fixture that MUST be a real capture — no stand-in. Used where the shape is asserted
     * rather than parsed, so a missing file is a broken test rather than a quieter one.
     */
    fun loadCaptured(name: String): String {
        val captured = ComfyFixtures::class.java.classLoader
            ?.getResourceAsStream("comfyui/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
        require(!captured.isNullOrBlank()) { "comfyui/$name must be a committed capture" }
        return captured
    }

    /**
     * A minimal but realistic SD1.5 text-to-image graph in API format: it carries `%PROMPT%`,
     * `%NEGATIVE%` and `%SEED%`, a literal `512` size the workflow owns, and — importantly — real
     * link arrays (`["4", 1]`) that must never be mistaken for substitution targets.
     */
    val SAMPLE_WORKFLOW: String = """
        {
          "3": {"class_type": "KSampler", "inputs": {
            "seed": "%SEED%", "steps": 20, "cfg": 8.0, "sampler_name": "euler",
            "scheduler": "normal", "denoise": 1.0,
            "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["5", 0]}},
          "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "sd_v1-5.safetensors"}},
          "5": {"class_type": "EmptyLatentImage", "inputs": {"width": 512, "height": 512, "batch_size": 1}},
          "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "%PROMPT%", "clip": ["4", 1]}},
          "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "%NEGATIVE%", "clip": ["4", 1]}},
          "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
          "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "ComfyUI", "images": ["8", 0]}}
        }
    """.trimIndent()

    /** The prompt id the [HISTORY_DONE] stand-in is keyed by. */
    const val HISTORY_PROMPT_ID = "3f2a1c44-0b6e-4a1d-9f0e-77c2a5e91b30"

    /**
     * A finished run exactly as `GET /history/{prompt_id}` returns it, transcribed from the
     * ComfyUI source: `execution.py`'s `task_done` writes `{"prompt", "outputs", "status"}` and then
     * updates it with the executor's `{"outputs", "meta"}`, and `main.py` fills `status_str` /
     * `completed` / `messages` from the run's own outcome.
     *
     * The filename and (empty) subfolder are what the recorder produces; `type` is `"output"` for
     * `SaveImage` and `"temp"` for `PreviewImage`.
     */
    val HISTORY_DONE: String = """
        {"$HISTORY_PROMPT_ID":{
          "prompt":[[1,"$HISTORY_PROMPT_ID",{},{}]],
          "outputs":{"9":{"images":[{"filename":"ComfyUI_00001_.png","subfolder":"","type":"output"}]}},
          "meta":{},
          "status":{"status_str":"success","completed":true,"messages":[]}}}
    """.trimIndent()
}
