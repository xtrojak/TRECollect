# Scripts Directory

This directory contains utility scripts for development, testing, and CI/CD.

## Available Scripts

### `run-tests.sh`
Runs unit tests locally with the same setup as CI.

**Usage:**
```bash
./scripts/run-tests.sh
```

**What it does:**
- Checks for `gradlew` in project root
- Runs `./gradlew test --console=plain`
- Shows test summary using `test-summary.sh`
- Exits with test exit code (0 = success, non-zero = failure)

### `test-summary.sh`
Extracts and displays test results from XML files.

**Usage:**
```bash
./scripts/test-summary.sh
```

**What it does:**
- Aggregates results from all test result directories (debug/release variants)
- Shows total, passed, failed, errors, skipped counts
- Lists failed test classes
- Finds HTML reports in all possible locations
- Can be used standalone or called by other scripts

**Environment variables:**
- `CI=true` - Suppresses interactive prompts (for CI environments)

### `check-device.sh`
Checks if an Android device or emulator is connected.

**Usage:**
```bash
./scripts/check-device.sh
```

**What it does:**
- Verifies `adb` is installed
- Lists all connected devices/emulators
- Tests device connectivity
- Provides helpful error messages if no device is found

**Exit codes:**
- `0` - Device found and ready
- `1` - No device found or not ready

### `run-instrumented-tests.sh`
Runs instrumented tests with device check.

**Usage:**
```bash
# Run all instrumented tests
./scripts/run-instrumented-tests.sh

# Run specific test class
./scripts/run-instrumented-tests.sh "com.trec.customlogsheets.database.SamplingSiteDaoTest"
```

**What it does:**
- Checks device connectivity using `check-device.sh`
- Runs `./gradlew connectedAndroidTest`
- Shows test summary if available
- Displays report location

## Notes

- All scripts should be run from the project root directory
- Scripts use relative paths (e.g., `app/build/...`) which assume execution from root
- Scripts are made executable automatically in CI workflows
- For local use, you may need to `chmod +x scripts/*.sh` once
