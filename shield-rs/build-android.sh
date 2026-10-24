#!/usr/bin/env bash
# Builds libarivna_shield.so for every Android ABI and copies it into the app.
set -euo pipefail
cd "$(dirname "$0")"
OUT=../app/src/main/jniLibs
declare -A ABI=( [aarch64-linux-android]=arm64-v8a [armv7-linux-androideabi]=armeabi-v7a [x86_64-linux-android]=x86_64 )
for target in "${!ABI[@]}"; do
  cargo build --release --lib --target "$target"
  mkdir -p "$OUT/${ABI[$target]}"
  cp "target/$target/release/libarivna_shield.so" "$OUT/${ABI[$target]}/"
done
ls -la "$OUT"/*/
