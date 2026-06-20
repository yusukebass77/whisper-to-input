#!/bin/bash
# SessionStart hook for Claude Code on the web.
#
# Installs the Android build toolchain (JDK 17 + Android SDK) and warms the
# Gradle cache so that `./gradlew` assemble / lint / unit-test work out of the
# box in remote sessions.
#
# NOTE on network policy: an Android build needs hosts that are NOT reachable
# under the most restrictive policies. The environment's network policy must
# allow (in addition to the default Maven Central / Gradle hosts):
#   - dl.google.com      (Android command-line tools + SDK packages)
#   - maven.google.com   (Android Gradle Plugin + AndroidX libraries)
#   - jitpack.io         (com.github.liuyueyi:quick-transfer-core dependency)
set -euo pipefail

# Only run inside Claude Code on the web (remote) environments.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# ---------------------------------------------------------------------------
# 1. Ensure a Gradle/AGP-compatible JDK (17). The base image ships JDK 21,
#    which Gradle 8.0 does not support, so install JDK 17 if it is missing.
# ---------------------------------------------------------------------------
JAVA17_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
if [ ! -d "$JAVA17_HOME" ]; then
  echo "Installing OpenJDK 17..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq openjdk-17-jdk-headless
fi
if [ -d "$JAVA17_HOME" ]; then
  export JAVA_HOME="$JAVA17_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# ---------------------------------------------------------------------------
# 2. Install the Android SDK command-line tools and required packages.
# ---------------------------------------------------------------------------
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
mkdir -p "$ANDROID_SDK_ROOT"

if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Installing Android command-line tools..."
  tmp_zip="$(mktemp)"
  curl -fsSL -o "$tmp_zip" "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/tmp"
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/tmp"
  unzip -q "$tmp_zip" -d "$ANDROID_SDK_ROOT/cmdline-tools/tmp"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  mv "$ANDROID_SDK_ROOT/cmdline-tools/tmp/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/tmp" "$tmp_zip"
fi

export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# Accept licenses (idempotent) and install the packages this project needs:
# compileSdk = 34, Android Gradle Plugin 8.1.2 -> build-tools 34.0.0.
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

# ---------------------------------------------------------------------------
# 3. Persist environment variables for the rest of the session.
# ---------------------------------------------------------------------------
{
  if [ -n "${JAVA_HOME:-}" ]; then
    echo "export JAVA_HOME=\"$JAVA_HOME\""
  fi
  echo "export ANDROID_SDK_ROOT=\"$ANDROID_SDK_ROOT\""
  echo "export ANDROID_HOME=\"$ANDROID_SDK_ROOT\""
  echo "export PATH=\"${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:\$PATH\""
} >> "$CLAUDE_ENV_FILE"

# ---------------------------------------------------------------------------
# 4. Warm the Gradle dependency cache so the first build/test is fast.
#    Tolerant of failure so a transient hiccup never blocks session startup.
# ---------------------------------------------------------------------------
( cd "$CLAUDE_PROJECT_DIR/android" && ./gradlew --no-daemon assembleDebug testDebugUnitTest ) \
  || echo "WARNING: Gradle warmup did not complete cleanly; check JDK/SDK/network policy."
