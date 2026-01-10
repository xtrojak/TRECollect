#!/bin/bash

# Script to run instrumented tests with device check
# Usage: ./run-instrumented-tests.sh [test-class]

set -e

# Check if device is connected
echo "Checking for connected devices..."
if ! ./scripts/check-device.sh; then
    echo ""
    echo "Cannot run instrumented tests without a connected device or emulator."
    exit 1
fi

echo ""
echo "Running instrumented tests..."

# Run tests with optional test class filter
if [ -n "$1" ]; then
    echo "Running specific test: $1"
    ./gradlew connectedAndroidTest --tests "$1" --console=plain
else
    echo "Running all instrumented tests..."
    ./gradlew connectedAndroidTest --console=plain
fi

# Show summary if test-summary.sh exists
if [ -f "scripts/test-summary.sh" ]; then
    echo ""
    echo "=== Test Summary ==="
    chmod +x scripts/test-summary.sh
    CI=true ./scripts/test-summary.sh || true
fi

echo ""
echo "✅ Instrumented tests completed!"
echo ""
echo "View detailed reports at:"
echo "  app/build/reports/androidTests/connected/index.html"
