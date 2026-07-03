#!/bin/bash
# Run Sakhi on the TABLET (BTX6226)
set -e
SERIAL=BTX6226230800843
ADB=/Users/nd-bharath/Library/Android/sdk/platform-tools/adb
cd "$(dirname "$0")"
export ANDROID_SERIAL=$SERIAL
./gradlew installDebug
"$ADB" -s "$SERIAL" shell am start -n org.armman.sakhi/.MainActivity
echo "✅ Sakhi launched on TABLET (BTX6226)"
