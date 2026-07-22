#!/bin/bash
# Run Sakhi on the MOBILE (A142 phone)
set -e
SERIAL=000583458001183
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
cd "$(dirname "$0")"
export ANDROID_SERIAL=$SERIAL
./gradlew installDebug
"$ADB" -s "$SERIAL" shell am start -n org.armman.sakhi/.MainActivity
echo "✅ Sakhi launched on MOBILE (A142)"
