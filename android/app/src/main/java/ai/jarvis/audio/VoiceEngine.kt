package ai.jarvis.audio

import ai.jarvis.BuildConfig
import ai.jarvis.data.VoicePreset
import ai.jarvis.data.presetFor
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * Speech output.
 *
 * * Sentences arrive one at a time and are queued with `QUEUE_ADD`, so the first
 *   word plays while Qwen is still generating the rest.
 * * Media is ducked with a transient-may-duck audio focus request, released as
 *   soon as the last utterance finishes.
 * * There is no PCM from platform TTS, so the speaking animation uses the
 *   engine's real word-range callbacks plus a syllable envelope. This is the
 *   documented fallback path; a Piper/VITS engine would supply PCM instead.
 * * `shutdown()` is called from the controller's teardown so the audio session
 *   and the TTS engine never outlive the service.
 */
class VoiceEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onAmplitude(value: Float)

        /** The word currently being spoken and its progress, from real TTS timings. */
        fun onWord(
            word: String,
            phase: Float,
        )

        fun onAllDone()

        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val pending = ArrayDeque<String>()
    private val texts = HashMap<String, String>()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var muted = false
    private var outstanding = 0
    private var counter = 0
    private var preset: VoicePreset = presetFor("jarvis")
    private var rate = 1.0f
    private var pitch = 0.85f
    private var volume = 0.9f
    private var maleVoice = true

    /** Whether the engine found a real male voice, rather than only deeper pitch. */
    private var maleApplied = false
    private var currentWord = ""
    private var wordStartedAt = 0L
    private var ticker: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false

    init {
        tts =
            TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    configureEngine()
                    tts?.setOnUtteranceProgressListener(progress)
                    drainPending()
                } else {
                    listener.onError("The speech engine could not start; replies are text only.")
                }
            }
    }

    fun configure(
        preset: VoicePreset,
        rate: Float,
        pitch: Float,
        volume: Float,
        maleVoice: Boolean,
    ) {
        this.preset = preset
        this.rate = rate.coerceIn(0.5f, 2.0f)
        this.pitch = pitch.coerceIn(0.5f, 2.0f)
        this.volume = volume.coerceIn(0f, 1f)
        this.maleVoice = maleVoice
        if (BuildConfig.DEBUG) {
            android.util.Log.d("JarvisVoice", "rate=${this.rate} pitch=${this.pitch} volume=${this.volume}")
        }
        if (ready) configureEngine()
    }

    fun setMuted(value: Boolean) {
        muted = value
        if (value) stop()
    }

    fun speak(
        text: String,
        question: Boolean,
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (muted) {
            listener.onAllDone()
            return
        }
        if (!ready) {
            pending.add(if (question) "$QUESTION_MARK$clean" else clean)
            return
        }
        speakNow(clean, question)
    }

    private fun speakNow(
        text: String,
        question: Boolean,
    ) {
        val engine = tts ?: return
        requestFocus()
        engine.setSpeechRate(rate)
        // A real male voice is used when installed; otherwise the preset's pitch
        // is lowered further so JARVIS still sounds male.
        val depth = if (maleVoice && !maleApplied) MALE_PITCH_FALLBACK else 1f
        engine.setPitch((pitch * depth * if (question) QUESTION_PITCH_BIAS else 1f).coerceIn(0.5f, 2f))
        val id = "u${++counter}"
        texts[id] = text
        outstanding += 1
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, id)
        if (result != TextToSpeech.SUCCESS) {
            outstanding = (outstanding - 1).coerceAtLeast(0)
            listener.onError("The speech engine rejected that utterance.")
            return
        }
        currentWord = ""
        wordStartedAt = System.nanoTime()
        startTicker()
    }

    /** Barge-in / interrupt: kill audio in the same frame, keep the session. */
    fun stop() {
        tts?.stop()
        outstanding = 0
        currentWord = ""
        pending.clear()
        ticker?.cancel()
        ticker = null
        listener.onWord("", 0f)
        listener.onAmplitude(0f)
        abandonFocus()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun drainPending() {
        while (pending.isNotEmpty() && ready) {
            val entry = pending.poll() ?: break
            val question = entry.startsWith(QUESTION_MARK)
            speakNow(entry.removePrefix(QUESTION_MARK), question)
        }
    }

    /**
     * Chooses the active voice.
     *
     * Android's platform TTS exposes no gender flag, so the male voices are
     * listed by name; each entry here was synthesised on the target device and
     * its median pitch measured (112-150 Hz, against 189-233 Hz for the rest).
     * Locals are preferred over network voices so speech still works offline.
     */
    private fun configureEngine() {
        val engine = tts ?: return
        val locale = Locale.forLanguageTag(preset.locale)
        engine.setLanguage(locale)
        val voices = engine.voices ?: return
        val sameLanguage = voices.filter { it.locale.language == locale.language }
        val male =
            if (maleVoice) {
                // Walk the preference list (deepest measured voice first) rather
                // than the engine's order, and prefer an installed local voice.
                MALE_VOICES
                    .mapNotNull { target ->
                        sameLanguage
                            .filter { baseName(it.name) == target }
                            .minByOrNull { it.isNetworkConnectionRequired }
                    }.firstOrNull()
            } else {
                null
            }
        val fallback =
            sameLanguage
                .sortedBy { if (it.locale.country == locale.country) 0 else 1 }
                .firstOrNull { !it.isNetworkConnectionRequired }
        val chosen = male ?: fallback
        maleApplied = male != null
        chosen?.let { engine.voice = it }
        if (BuildConfig.DEBUG) android.util.Log.i("JarvisVoice", "voice=${chosen?.name} male=$maleApplied")
    }

    private fun baseName(name: String): String =
        name
            .substringBefore('#')
            .removeSuffix("-local")
            .removeSuffix("-network")
            .lowercase(Locale.ROOT)

    private val progress =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                currentWord = ""
                wordStartedAt = System.nanoTime()
                startTicker()
            }

            override fun onRangeStart(
                utteranceId: String?,
                start: Int,
                end: Int,
                frame: Int,
            ) {
                val text = texts[utteranceId] ?: return
                if (start in text.indices && end in start..text.length) {
                    currentWord = text.substring(start, end)
                    wordStartedAt = System.nanoTime()
                }
            }

            override fun onDone(utteranceId: String?) {
                texts.remove(utteranceId)
                outstanding = (outstanding - 1).coerceAtLeast(0)
                if (outstanding == 0) finishSpeaking()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                texts.remove(utteranceId)
                outstanding = (outstanding - 1).coerceAtLeast(0)
                listener.onError("Speech playback failed.")
                if (outstanding == 0) finishSpeaking()
            }

            override fun onStop(
                utteranceId: String?,
                interrupted: Boolean,
            ) {
                texts.remove(utteranceId)
                if (interrupted) {
                    outstanding = 0
                    finishSpeaking()
                }
            }
        }

    private fun finishSpeaking() {
        ticker?.cancel()
        ticker = null
        listener.onWord("", 0f)
        listener.onAmplitude(0f)
        abandonFocus()
        listener.onAllDone()
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker =
            scope.launch {
                while (isActive) {
                    val word = currentWord
                    val phase = phaseFor(word)
                    listener.onWord(word, phase)
                    listener.onAmplitude(envelopeFor(word, phase))
                    delay(16)
                }
            }
    }

    private fun phaseFor(word: String): Float {
        if (word.isBlank()) return 0f
        val elapsedMs = (System.nanoTime() - wordStartedAt) / 1_000_000.0
        val duration = 150.0 + word.length * 45.0
        return ((elapsedMs / duration).coerceIn(0.0, 0.999)).toFloat()
    }

    private fun envelopeFor(
        word: String,
        phase: Float,
    ): Float {
        if (word.isBlank()) {
            val elapsedMs = (System.nanoTime() - wordStartedAt) / 1_000_000.0
            // Before the first word callback: a low, true-timed breath rather
            // than a canned loop.
            return (0.18 + 0.12 * abs(sin(elapsedMs / 140.0))).toFloat()
        }
        val attack = (phase / 0.18f).coerceAtMost(1f)
        val release = ((1f - phase) / 0.25f).coerceAtMost(1f)
        val shape = minOf(attack, release).coerceAtLeast(0f)
        val weight = minOf(1f, 0.45f + 0.16f * word.count { it.lowercaseChar() in "aeiouy" })
        return (shape * weight).coerceIn(0f, 1f)
    }

    private fun requestFocus() {
        if (focusHeld) return
        val manager = audioManager ?: return
        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
        focusRequest = request
        manager.requestAudioFocus(request)
        focusHeld = true
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
        focusHeld = false
    }

    private companion object {
        const val QUESTION_PITCH_BIAS = 1.06f
        const val MALE_PITCH_FALLBACK = 0.85f

        /** Measured male system voices (median F0 112-150 Hz on a Redmi 10). */
        val MALE_VOICES =
            listOf(
                "en-gb-x-gbd",
                "en-gb-x-rjs",
                "en-us-x-iol",
                "en-gb-x-gbb",
            )
        const val QUESTION_MARK = "\u0001"
    }
}
