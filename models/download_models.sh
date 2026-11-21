#!/usr/bin/env bash
# Downloads the optional on-device models. Nothing here is committed.
#
#   ./models/download_models.sh            # whisper base.en + openWakeWord + Piper
#   WITH_QWEN=1 ./models/download_models.sh  # ... plus Qwen2.5-3B Q4_K_M (~2 GB)
#
# The app does not yet consume Qwen GGUF or Piper directly (see README
# "Engine status"); the files are staged for the feature builds.
set -euo pipefail
DEST="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
mkdir -p "$DEST"

fetch() {
    local url="$1" out="$2"
    if [ -f "$DEST/$out" ]; then
        printf '  exists   %s\n' "$out"
        return
    fi
    printf '  download %s\n' "$out"
    curl -fL --retry 3 --retry-delay 2 -o "$DEST/$out.part" "$url"
    mv "$DEST/$out.part" "$DEST/$out"
}

echo "[models] destination: $DEST"

# Whisper ggml, for the `whisper` feature build (core/src/asr.rs).
fetch "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin" \
      "ggml-base.en.bin"

# openWakeWord "hey jarvis" pipeline: mel -> embedding -> classifier.
fetch "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx" \
      "melspectrogram.onnx"
fetch "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx" \
      "embedding_model.onnx"
fetch "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/hey_jarvis_v0.1.onnx" \
      "hey_jarvis_v0.1.onnx"

# Piper en_GB male, for the local TTS integration.
fetch "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx" \
      "en_GB-alan-medium.onnx"
fetch "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx.json" \
      "en_GB-alan-medium.onnx.json"

if [ "${WITH_QWEN:-0}" = "1" ]; then
    fetch "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf" \
          "qwen2.5-3b-instruct-q4_k_m.gguf"
fi

echo
echo "[models] Place the files on the device with:"
echo "  adb push $DEST/ggml-base.en.bin /data/local/tmp/jarvis/"
echo "Alternatively copy them into the app's private models directory via"
echo "  adb shell run-as ai.jarvis mkdir -p files/models"
echo "and push with 'adb shell run-as ai.jarvis sh -c \"cat > files/models/...\"'."
echo "[models] These files are licensed by their upstream projects; check each."
