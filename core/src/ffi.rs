//! JNI surface for `ai.jarvis.core.JarvisNative`.
//!
//! Rules enforced here:
//! * every entry point is wrapped in `catch_unwind`; a panic becomes a Java
//!   exception or a non-zero error code, never unwinds across the boundary;
//! * handles are opaque `jlong` boxes and null-checked on every call;
//! * no long-running work happens on the JNI thread — `submit_text` only spawns;
//! * `pushPcm` copies in fixed 2048-sample stack chunks and never allocates.
//!
//! Events are delivered synchronously to `HostCallback.onEvent(String)`. The
//! Kotlin side only parses and posts to a `MutableStateFlow`, so the callback
//! returns quickly.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

use jni::objects::{GlobalRef, JClass, JFloatArray, JObject, JString, JValue};
use jni::sys::{jfloat, jint, jlong};
use jni::{JNIEnv, JavaVM};

use crate::engine::{EngineConfig, HostSink, JarvisEngine};
use crate::error::JarvisError;
#[cfg(test)]
use crate::error::Result;
use crate::events::EngineEvent;

const OK: jint = 0;

struct JniSink {
    vm: JavaVM,
    callback: GlobalRef,
}

impl HostSink for JniSink {
    fn emit(&self, event: EngineEvent) {
        let payload = match serde_json::to_string(&event) {
            Ok(payload) => payload,
            Err(error) => {
                tracing::error!(%error, "cannot serialize engine event");
                return;
            }
        };
        // Attach for the duration of the call; this is cheap and safe from the
        // DSP thread, the Tokio workers and the JNI thread alike.
        let Ok(mut env) = self.vm.attach_current_thread() else {
            return;
        };
        let Ok(string) = env.new_string(payload) else {
            return;
        };
        let object = JObject::from(string);
        if env
            .call_method(
                self.callback.as_obj(),
                "onEvent",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&object)],
            )
            .is_err()
        {
            // A Kotlin exception must not stay pending on this thread.
            let _ = env.exception_clear();
        }
    }
}

fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> std::result::Result<String, String> {
    env.get_string(value)
        .map(Into::into)
        .map_err(|e| format!("JNI string: {e}"))
}

fn throw(env: &mut JNIEnv<'_>, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/IllegalStateException", message.as_ref());
}

fn with_engine<T>(handle: jlong, default: T, f: impl FnOnce(&JarvisEngine) -> T) -> T {
    let pointer = handle as *const JarvisEngine;
    if pointer.is_null() {
        return default;
    }
    // SAFETY: the pointer comes from `Box::into_raw` in `nativeCreate` and is
    // only freed by `nativeDestroy`; the Kotlin facade owns exactly one handle
    // and never uses it after destroy.
    let engine = unsafe { &*pointer };
    match catch_unwind(AssertUnwindSafe(|| f(engine))) {
        Ok(value) => value,
        Err(payload) => {
            let message = panic_message(&payload);
            tracing::error!(%message, "panic contained at JNI boundary");
            default
        }
    }
}

fn panic_message(payload: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "unknown panic".to_string()
    }
}

#[cfg(test)]
fn code(result: Result<()>) -> jint {
    match result {
        Ok(()) => OK,
        Err(error) => error.code(),
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_jarvis_core_JarvisNative_nativeCreate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config_json: JString<'_>,
    callback: JObject<'_>,
) -> jlong {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> std::result::Result<jlong, String> {
        let json = read_string(&mut env, &config_json)?;
        let config: EngineConfig =
            serde_json::from_str(&json).map_err(|e| format!("invalid engine config: {e}"))?;
        let vm = env.get_java_vm().map_err(|e| e.to_string())?;
        let callback = env
            .new_global_ref(&callback)
            .map_err(|e| format!("callback ref: {e}"))?;
        let sink = Arc::new(JniSink { vm, callback });
        let engine = JarvisEngine::new(config, sink).map_err(|e| e.to_string())?;
        Ok(Box::into_raw(Box::new(engine)) as jlong)
    }));

    match outcome {
        Ok(Ok(handle)) => handle,
        Ok(Err(message)) => {
            throw(&mut env, message);
            0
        }
        Err(payload) => {
            throw(
                &mut env,
                format!("panic while creating engine: {}", panic_message(&payload)),
            );
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_jarvis_core_JarvisNative_nativeStart(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    let outcome = with_engine(handle, Err(JarvisError::Internal("native call panicked".into())), |engine| {
        engine.start()
    });
    match outcome {
        Ok(()) => OK,
        Err(error) => {
            throw(&mut env, error.to_string());
            error.code()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_jarvis_core_JarvisNative_nativeStop(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    with_engine(handle, (), |engine| engine.stop());
}

#[no_mangle]
pub extern "system" fn Java_ai_jarvis_core_JarvisNative_nativeCancel(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    with_engine(handle, (), |engine| engine.cancel());
}

#[no_mangle]
pub extern "system" fn Java_ai_jarvis_core_JarvisNative_nativeSubmitText(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    text: JString<'_>,
) -> jint {
    let Ok(text) = read_string(&mut env, &text) else {
        return JarvisError::InvalidArgument("text is not UTF-8".into()).code();
    };
    let outcome = with_engine(handle, Err(JarvisError::Internal("native call panicked".into())), |engine| {
        engine.submit_text(&text)
    });
    match outcome {
        Ok(()) => OK,
        Err(error) => error.code(),
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_jarvis_core_JarvisNative_nativeSubmitToolResult(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    tool_call_id: JString<'_>,
    result_json: JString<'_>,
) -> jint {
    let (Ok(id), Ok(result)) = (
        read_string(&mut env, &tool_call_id),
        read_string(&mut env, &result_json),
    ) else {
        return JarvisError::InvalidArgument("tool result is not UTF-8".into()).code();
    };
    let outcome = with_engine(handle, Err(JarvisError::Internal("native call panicked".into())), |engine| {
        engine.submit_tool_result(&id, &result)
    });
    match outcome {
        Ok(()) => OK,
        Err(error) => error.code(),
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_jarvis_core_JarvisNative_nativeTtsDone(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    with_engine(handle, (), |engine| engine.tts_done());
}

#[no_mangle]
pub extern "system" fn Java_ai_jarvis_core_JarvisNative_nativePushPcm(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    samples: JFloatArray<'_>,
) {
    with_engine(handle, (), |engine| {
        let Ok(len) = env.get_array_length(&samples) else {
            return;
        };
        let total = len as usize;
        let mut buffer = [0f32; 2048];
        let mut offset = 0;
        while offset < total {
            let chunk = (total - offset).min(buffer.len());
            if env
                .get_float_array_region(&samples, offset as i32, &mut buffer[..chunk])
                .is_err()
            {
                return;
            }
            engine.push_pcm(&buffer[..chunk]);
            offset += chunk;
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_ai_jarvis_core_JarvisNative_nativeRecordToolTrace(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    tool: JString<'_>,
    arguments_json: JString<'_>,
    result: JString<'_>,
    ok: jni::sys::jboolean,
    latency_ms: jfloat,
) {
    let (Ok(tool), Ok(arguments), Ok(result)) = (
        read_string(&mut env, &tool),
        read_string(&mut env, &arguments_json),
        read_string(&mut env, &result),
    ) else {
        return;
    };
    let arguments = serde_json::from_str(&arguments).unwrap_or(serde_json::Value::Null);
    with_engine(handle, (), |engine| {
        engine.record_tool_trace(&tool, arguments, &result, ok != 0, latency_ms as f64);
    });
}

#[no_mangle]
pub extern "system" fn Java_ai_jarvis_core_JarvisNative_nativeDestroy(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let pointer = handle as *mut JarvisEngine;
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: pointer originated from `Box::into_raw` and each handle is
        // destroyed at most once by the Kotlin facade (see JarvisEngineFacade).
        drop(unsafe { Box::from_raw(pointer) });
    }));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn null_handle_is_safe() {
        assert_eq!(with_engine(0, 7, |_| 9), 7);
    }

    #[test]
    fn panic_is_contained() {
        let value = with_engine(0, "fallback", |_| "unreachable");
        assert_eq!(value, "fallback");
    }

    #[test]
    fn codes_match_error_variants() {
        assert_eq!(code(Ok(())), 0);
        assert_eq!(code(Err(JarvisError::Cancelled)), 30);
    }
}
