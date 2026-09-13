#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
export JAVA_HOME="${JAVA_HOME:-$project_dir/.tools/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$project_dir/.tools/android}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$project_dir/.tools/android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$project_dir/.tools/gradle-home}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo 'Set JAVA_HOME to JDK 17. See README.md.' >&2
  exit 1
fi
if [[ -x "$project_dir/.tools/gradle-8.11.1/bin/gradle" ]]; then
  exec "$project_dir/.tools/gradle-8.11.1/bin/gradle" --console=plain "$@"
fi
exec ./gradlew --console=plain "$@"
