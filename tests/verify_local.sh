#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc \
  "$ROOT/app/src/main/java/com/secondbrain/ai/data/Models.kt" \
  "$ROOT/app/src/main/java/com/secondbrain/ai/data/ReportEngine.kt" \
  "$ROOT/app/src/main/java/com/secondbrain/ai/util/ReminderPlan.kt" \
  "$ROOT/tests/local_verify.kt" -include-runtime -d "$TMP/checks.jar"
java -jar "$TMP/checks.jar"
