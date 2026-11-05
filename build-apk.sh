#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
  echo 'Android SDK not detected. Install Android Studio + Android SDK API 35, then set ANDROID_HOME.' >&2
  exit 2
fi
if command -v gradle >/dev/null 2>&1; then
  gradle :app:assembleDebug
else
  echo 'Gradle is not installed. This source archive excludes the binary Gradle wrapper jar because dependencies were unavailable offline.' >&2
  echo 'Install Gradle 8.11.1 or regenerate Gradle Wrapper on a machine with internet, then run :app:assembleDebug.' >&2
  exit 2
fi
echo 'APK output: app/build/outputs/apk/debug/app-debug.apk'
