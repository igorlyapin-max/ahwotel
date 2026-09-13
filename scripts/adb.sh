#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$project_dir/.tools/android-user}"
sdk_dir="${ANDROID_HOME:-$project_dir/.tools/android}"
exec "$sdk_dir/platform-tools/adb" "$@"
