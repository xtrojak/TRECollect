#!/bin/bash

# Script to check if Android device or emulator is connected
# Usage: ./check-device.sh

set -e

echo "Checking for connected Android devices/emulators..."
echo ""

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "❌ Error: adb command not found"
    echo ""
    echo "Please install Android SDK Platform Tools:"
    echo "  - macOS: brew install android-platform-tools"
    echo "  - Linux: sudo apt-get install android-tools-adb"
    echo "  - Or download from: https://developer.android.com/studio/releases/platform-tools"
    exit 1
fi

# Start adb server if not running
adb start-server > /dev/null 2>&1

# Get list of devices
DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" | awk '{print $1}')

if [ -z "$DEVICES" ]; then
    echo "❌ No devices or emulators connected"
    echo ""
    echo "To connect a device:"
    echo "  1. Enable Developer Options on your Android device"
    echo "  2. Enable USB Debugging"
    echo "  3. Connect via USB and accept the debugging prompt"
    echo ""
    echo "To start an emulator:"
    echo "  1. Open Android Studio"
    echo "  2. Tools > Device Manager"
    echo "  3. Create/Start an AVD (Android Virtual Device)"
    echo "     Recommended: Use API 30 (Android 11) for best compatibility"
    echo "  4. Or use: emulator -avd <avd_name>"
    echo ""
    echo "Current adb devices:"
    adb devices
    exit 1
fi

# Count devices
DEVICE_COUNT=$(echo "$DEVICES" | wc -l | tr -d ' ')

echo "✅ Found $DEVICE_COUNT connected device(s):"
echo ""
adb devices
echo ""

# Check if we can run instrumented tests
echo "Testing device connectivity..."
if adb shell echo "test" > /dev/null 2>&1; then
    echo "✅ Device is ready for instrumented tests"
    echo ""
    echo "You can now run instrumented tests with:"
    echo "  ./gradlew connectedAndroidTest"
    exit 0
else
    echo "⚠️  Device is connected but may not be ready"
    echo "  Try: adb shell echo 'test'"
    exit 1
fi
