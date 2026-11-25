package ai.jarvis.data

/**
 * Voice presets.
 *
 * All four are rendered by the platform TTS engine in this build, tuned with
 * pitch/rate; this is the documented fallback tier from the brief. The `engine`
 * field records which offline engine a preset is intended for, so the Piper
 * integration can take over without touching the UI.
 */
data class VoicePreset(
    val id: String,
    val label: String,
    val description: String,
    val locale: String,
    val pitch: Float,
    val rate: Float,
    val engine: VoiceEngineKind,
)

enum class VoiceEngineKind { PIPER, SHERPA, PLATFORM }

val VOICE_PRESETS =
    listOf(
        VoicePreset(
            id = "jarvis",
            label = "Seven",
            description = "British male, dry, unhurried. Falls back to the closest en-GB voice installed.",
            locale = "en-GB",
            pitch = 0.82f,
            // Normal talking speed: anything below 1.0 reads as drawled.
            rate = 1.0f,
            engine = VoiceEngineKind.PLATFORM,
        ),
        VoicePreset(
            id = "calm",
            label = "Calm",
            description = "Neutral and slower. Better for long answers and accessibility.",
            locale = "en-GB",
            pitch = 1.0f,
            rate = 0.95f,
            engine = VoiceEngineKind.PLATFORM,
        ),
        VoicePreset(
            id = "warm",
            label = "Warm",
            description = "Lower, softer delivery with a little more air.",
            locale = "en-US",
            pitch = 0.95f,
            rate = 1.0f,
            engine = VoiceEngineKind.PLATFORM,
        ),
        VoicePreset(
            id = "cloned",
            label = "Cloned",
            description = "Piper multi-speaker / user ONNX voice. Requires models/download_models.sh.",
            locale = "en-GB",
            pitch = 0.85f,
            rate = 1.0f,
            engine = VoiceEngineKind.PIPER,
        ),
    )

fun presetFor(id: String): VoicePreset = VOICE_PRESETS.firstOrNull { it.id == id } ?: VOICE_PRESETS[0]
