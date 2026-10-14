#!/usr/bin/env bash
# Builds AutoFlow's debug APK. Needs JDK 17+ and the Android SDK (platform 35), plus internet
# access to Google's Maven (dl.google.com) and Maven Central for the first build.
# Output: dist/AutoFlow-debug.apk
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v java >/dev/null; then
  echo "Java 17+ is needed (for example: sudo apt install openjdk-17-jdk)." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && ! -f local.properties ]]; then
  for d in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /usr/local/lib/android/sdk /opt/android-sdk; do
    if [[ -d "$d" ]]; then export ANDROID_HOME="$d"; break; fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && ! -f local.properties ]]; then
  echo "Android SDK not found. Set ANDROID_HOME, or install command-line tools and run:" >&2
  echo "  sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'" >&2
  exit 1
fi

chmod +x gradlew
./gradlew :core:test :app:assembleDebug "$@"

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/AutoFlow-debug.apk
echo
echo "Done: dist/AutoFlow-debug.apk"
echo "Install on a connected tablet with: adb install -r dist/AutoFlow-debug.apk"
