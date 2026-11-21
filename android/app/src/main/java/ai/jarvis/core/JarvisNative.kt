package ai.jarvis.core

/**
 * Hand-written JNI bridge to `libjarvis_core.so`.
 *
 * uniffi was evaluated and rejected for this build: it needs a bindgen run per
 * ABI and generated Kotlin that duplicates the small surface below, while the
 * one thing we actually need from it — typed errors — is already carried by the
 * numeric codes and JSON events. See DESIGN.md.
 *
 * Threading contract for [nativePushPcm]: audio samples must come from exactly
 * one capture thread at a time. Rust wraps a lock-free SPSC producer and relies
 * on that; calling it from two threads is undefined behaviour.
 *
 * Every method returns 0 on success when it returns an Int. Non-zero values are
 * stable error codes from `JarvisError::code()`.
 */
object JarvisNative {
    init {
        System.loadLibrary("jarvis_core")
    }

    /** Event sink. Called from Rust worker threads, never on the main thread. */
    fun interface HostCallback {
        fun onEvent(json: String)
    }

    external fun nativeCreate(
        configJson: String,
        callback: HostCallback,
    ): Long

    external fun nativeStart(handle: Long): Int

    external fun nativeStop(handle: Long)

    external fun nativeCancel(handle: Long)

    external fun nativeSubmitText(
        handle: Long,
        text: String,
    ): Int

    external fun nativeSubmitToolResult(
        handle: Long,
        toolCallId: String,
        resultJson: String,
    ): Int

    external fun nativeTtsDone(handle: Long)

    external fun nativePushPcm(
        handle: Long,
        samples: FloatArray,
    )

    external fun nativeRecordToolTrace(
        handle: Long,
        tool: String,
        argumentsJson: String,
        result: String,
        ok: Boolean,
        latencyMs: Float,
    )

    external fun nativeDestroy(handle: Long)

    const val CODE_NOT_CONFIGURED = 10
    const val CODE_PERMISSION_DENIED = 11
    const val CODE_INVALID_ARGUMENT = 12
    const val CODE_AUDIO = 20
    const val CODE_NETWORK = 21
    const val CODE_MODEL = 22
    const val CODE_CANCELLED = 30
    const val CODE_INTERNAL = 40
}
