package ai.jarvis.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max

/**
 * 16 kHz mono float capture, 20 ms reads, pushed straight into the Rust ring.
 *
 * AudioRecord on API 26+ uses the fast-track path where the device supports it,
 * which is the same low-latency path Oboe selects for input. Keeping capture in
 * Kotlin means the microphone permission, foreground-service type and audio
 * focus all live in one place; the realtime DSP stays in Rust.
 */
class AudioCapture(
    private val onSamples: (FloatArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
        if (minBuffer <= 0) {
            onError("Device cannot capture 16 kHz float audio ($minBuffer)")
            return false
        }
        val recorder =
            runCatching {
                AudioRecord
                    .Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    ).setBufferSizeInBytes(max(minBuffer, FRAME_SAMPLES * 4) * 2)
                    .build()
            }.getOrElse {
                onError("AudioRecord init failed: ${it.message}")
                return false
            }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("Microphone unavailable")
            return false
        }
        record = recorder
        running = true
        recorder.startRecording()
        thread =
            Thread({
                val frame = FloatArray(FRAME_SAMPLES)
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO,
                )
                while (running) {
                    val read = recorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        if (read == frame.size) {
                            onSamples(frame)
                        } else {
                            onSamples(frame.copyOf(read))
                        }
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord.read = $read")
                        break
                    }
                }
            }, "jarvis-capture").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(400)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    companion object {
        private const val TAG = "JarvisAudio"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320 // 20 ms
    }
}
