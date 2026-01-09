#!/bin/bash
# Test execution script that mirrors CI workflow
# Run: ./run-tests.sh

set -e  # Exit on error

echo "=========================================="
echo "Running Unit Tests (CI Mode)"
echo "=========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if gradlew exists
if [ ! -f "./gradlew" ]; then
    echo -e "${RED}Error: gradlew not found. Are you in the project root?${NC}"
    exit 1
fi

# Make gradlew executable
chmod +x ./gradlew

echo "Step 1: Running unit tests..."
echo "----------------------------------------"
echo ""

# Run tests with plain console output (same as CI)
if ./gradlew test --console=plain; then
    TEST_EXIT_CODE=0
    echo ""
    echo -e "${GREEN}✅ Tests completed successfully${NC}"
else
    TEST_EXIT_CODE=$?
    echo ""
    echo -e "${RED}❌ Tests failed with exit code: $TEST_EXIT_CODE${NC}"
fi

echo ""
# Use the shared test-summary.sh script to avoid duplication
./test-summary.sh

# Exit with the test exit code (same as CI)
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✅ Test execution completed successfully${NC}"
    exit 0
else
    echo -e "${RED}❌ Test execution failed${NC}"
    exit $TEST_EXIT_CODE
fi
