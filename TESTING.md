# Testing Guide

This document describes the testing setup, how to run tests, and how to interpret results for the TRECollect app.

## Overview

Unit tests are located in `app/src/test/` and run on the JVM without requiring an Android device or emulator. They are designed to be fast, deterministic, and CI-ready.

Instrumented tests are located in `app/src/androidTest/` and run on Android devices/emulators. They test UI interactions, database operations, and Android-specific functionality.

## Test Environment Isolation

**Important:** The app automatically detects when running in a test environment and skips ownCloud folder creation to prevent creating test folders on the server. This detection works for:
- Instrumented tests (using `InstrumentationRegistry`)
- AndroidJUnitRunner-based tests
- Tests running in test processes

If you need to test ownCloud functionality, you should mock `OwnCloudManager` or use a test-specific ownCloud instance.

## Important: UI Testing Framework

**The project uses UI Automator instead of Espresso for UI tests.**

UI Automator was chosen because:
- ✅ Works on Android 15 (API 36) without compatibility issues
- ✅ More stable on newer Android versions
- ✅ Can test across apps and system UI

**Emulator configuration:**
- API Level: 30 (Android 11) or higher (including Android 15)
- Architecture: x86_64 (Intel) or arm64-v8a (Apple Silicon)
- Profile: Any (e.g., Pixel 4, Nexus 6)

**Note:** Espresso 3.6.1 has compatibility issues with Android 15 (API 36) due to `InputManager` API changes. UI Automator does not have this limitation.

## Current Test Coverage

### Unit Tests

### Data Layer Tests
- **ConvertersTest** - Tests enum conversion logic for Room database TypeConverters
  - SiteStatus enum conversions (ONGOING ↔ 0, FINISHED ↔ 1)
  - UploadStatus enum conversions (NOT_UPLOADED ↔ 0, UPLOADING ↔ 1, UPLOADED ↔ 2, UPLOAD_FAILED ↔ 3)
  - Error handling for invalid ordinals
  - Round-trip conversion tests

- **SamplingSiteTest** - Tests data class behavior
  - Default values
  - Equality and hashCode
  - Copy operations
  - Enum value validation

- **FormDataTest** - Tests form data structures
  - Timestamp generation (ISO 8601 format)
  - FormFieldValue with different field types (text, multiselect, GPS, photo, table, dynamic)
  - FormData equality and data integrity

- **FormConfigLoaderTest** - Tests JSON form configuration parsing
  - Simple and complex form configurations
  - All field types (text, number, multiselect, GPS, photo, table, dynamic, section)
  - Error handling for invalid JSON

- **SettingsPreferencesTest** - Tests preference storage utilities
  - Getter/setter operations
  - UUID generation
  - OwnCloud folder verification

- **FolderStructureHelperTest** - Tests folder name constants

- **MainViewModelFactoryTest** - Tests ViewModel factory creation

- **MainViewModelTest** - Tests ViewModel business logic (basic tests)

### Instrumented Tests

### Database Tests
- **AppDatabaseTest** - Database creation, TypeConverter functionality
- **SamplingSiteDaoTest** - CRUD operations for SamplingSite entities
- **FormCompletionDaoTest** - CRUD operations for FormCompletion entities

### UI Tests
- **MainActivityTest** - UI element presence, basic interactions, layout verification

## Running Tests

### Recommended: Use Test Script (Matches CI)

The easiest way to run tests locally with the same setup as CI:

```bash
./scripts/run-tests.sh
```

This script:
- Runs tests the same way as CI (`./gradlew test --console=plain`)
- Shows a detailed summary with test counts
- Lists failed tests
- Provides links to HTML reports
- Exits with the same code as CI (0 = success, non-zero = failure)

### Running Unit Tests

```bash
# Run all unit tests (recommended)
./scripts/run-tests.sh

# Or use Gradle directly
./gradlew test

# View test summary
./scripts/test-summary.sh
```

### Running Instrumented Tests

**Important:** Use an emulator with API 30 or lower for best compatibility.

```bash
# Check if device/emulator is connected
./scripts/check-device.sh

# Run all instrumented tests
./scripts/run-instrumented-tests.sh

# Or use Gradle directly
./gradlew connectedAndroidTest

# Run specific test class
./scripts/run-instrumented-tests.sh "com.trec.trecollect.database.SamplingSiteDaoTest"
```

### Running Specific Tests

```bash
# Run specific unit test class
./gradlew test --tests "com.trec.trecollect.data.ConvertersTest"

# Run specific unit test method
./gradlew test --tests "com.trec.trecollect.data.ConvertersTest.toSiteStatus_roundTrip"

# Run specific instrumented test class
./gradlew connectedAndroidTest --tests "com.trec.trecollect.database.AppDatabaseTest"
```

## Viewing Test Results

### HTML Reports

After running tests, view the HTML report at:

```
app/build/reports/tests/testDebugUnitTest/index.html
# or
app/build/reports/tests/testReleaseUnitTest/index.html
```

For instrumented tests:
```
app/build/reports/androidTests/connected/index.html
```

The HTML report shows:
- ✅ Green checkmarks for passed tests
- ❌ Red X for failed tests
- Test execution time
- Full error messages and stack traces
- Organized by test class and method

### XML Reports

Located at:
```
app/build/test-results/testDebugUnitTest/
app/build/test-results/testReleaseUnitTest/
app/build/test-results/androidTests/
```

These are used by CI systems and IDEs. The `scripts/test-summary.sh` script aggregates results from all test result directories.

## CI Integration

### How CI Checks Test Results

**Yes, CI explicitly checks if tests passed!**

The GitHub Actions workflows:
1. Run tests - **This will fail the CI job if any test fails**
2. Show a test summary using `scripts/test-summary.sh`
3. Upload HTML reports as artifacts
4. Publish test results to GitHub (comments on PRs and annotations)

### Understanding CI Output

#### Success Case:
```
BUILD SUCCESSFUL in 10s
Test Results: SUCCESS (260 tests, 260 passed, 0 failed, 0 skipped)
✅ All tests passed!
```

#### Failure Case:
```
BUILD FAILED
Test Results: FAILURE (260 tests, 246 passed, 14 failed, 0 skipped)
❌ Some tests failed!
```

The CI job will show as **failed** (red X) if any test fails.

### Viewing CI Test Results

1. **GitHub Actions UI:**
   - Go to the "Actions" tab
   - Click on the workflow run
   - See the "Show test summary" step for detailed counts
   - Download test report artifacts to view HTML reports

2. **PR Comments (if enabled):**
   - Test results are automatically posted as PR comments
   - Shows pass/fail status for each test class
   - Clickable links to see details

### CI Configuration

The project uses GitHub Actions with:
- **Unit Tests**: JDK 17 (Temurin distribution), runs on ubuntu-latest
- **Instrumented Tests**: JDK 17, runs on macos-14 with API 30 emulator (ARM64)
- Automatic test result publishing
- HTML report artifacts

For other CI systems (GitLab, Jenkins, etc.), adapt the workflow:
```yaml
test:
  script:
    - ./gradlew test
```

## Test Dependencies

The following testing libraries are included:

### Unit Tests (JVM)
- **JUnit 4.13.2** - Test framework
- **Mockito 5.1.1** - Mocking framework
- **Mockito-Kotlin 5.1.0** - Kotlin extensions for Mockito
- **Kotlin Coroutines Test 1.7.3** - Coroutine testing utilities
- **Turbine 1.0.0** - Flow testing library
- **AssertJ 3.24.2** - Fluent assertions
- **org.json:json 20231013** - JVM-compatible JSON library

### Instrumented Tests (Android)
- **JUnit 1.2.1** - Android JUnit extensions
- **UI Automator 2.3.0** - UI testing framework (works on Android 15)
  - Alternative to Espresso with better Android 15 compatibility
  - Can test across apps and system UI
- **Espresso 3.6.1** - UI testing framework (kept for reference, not actively used)
  - Has compatibility issues with Android 15 (API 36)
  - espresso-core: Core Espresso functionality
  - espresso-contrib: Additional matchers and actions
  - espresso-intents: Intent testing support
- **AndroidX Test 1.6.2** - Test runner and rules
- **Room Testing 2.6.1** - Room database testing utilities
- **Turbine 1.0.0** - Flow testing library
- **Kotlin Coroutines Test 1.7.3** - Coroutine testing utilities

## Troubleshooting

### Tests Fail with "Unresolved reference"
- Make sure you're running unit tests (`./gradlew test`), not instrumented tests
- Mock Android dependencies instead of using real classes
- Wrap Android API calls (like `android.util.Log`) in try-catch blocks

### Tests are slow
- Check for network calls or file I/O - these should be mocked
- Ensure tests are running in parallel (default Gradle behavior)

### Tests Pass Locally but Fail in CI
- Check Java version (CI uses JDK 17)
- Check for environment-specific issues
- Review CI logs for full error messages

### Can't See Test Count
- Run with `--info` flag: `./gradlew test --info`
- Check the HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Use the test summary script: `./scripts/test-summary.sh`

### CI Shows "Success" but Tests Failed
This shouldn't happen with the current setup. If it does:
- Check that the workflow doesn't have `continue-on-error: true`
- Verify the workflow file is correct
- Check if tests are actually running (look for "Test Results:" in output)

### Test Results Directory Not Found
- Make sure tests have been run: `./gradlew test`
- Check for results in: `app/build/test-results/testDebugUnitTest/` or `app/build/test-results/testReleaseUnitTest/`
- The `scripts/test-summary.sh` script automatically checks all possible locations

### UI Tests Use UI Automator (Not Espresso)

**The project uses UI Automator for UI testing**, which works on all Android versions including Android 15 (API 36).

**Why UI Automator:**
- ✅ Works on Android 15 without compatibility issues
- ✅ More stable on newer Android versions
- ✅ Can test across apps and system UI

**If you see Espresso-related errors:**
- The project has migrated from Espresso to UI Automator
- All UI tests now use UI Automator APIs
- Espresso dependencies are kept for reference but not actively used

**UI Automator vs Espresso:**
- UI Automator: More verbose but more stable, works on Android 15
- Espresso: More concise but has Android 15 compatibility issues

**For new UI tests:**
- Use UI Automator APIs (`UiDevice`, `UiSelector`, `UiObject`)
- See `MainActivityTest.kt` for examples

## Helper Scripts

### `scripts/run-tests.sh`
Runs tests locally with the same setup as CI, including:
- JDK version check
- Execute permission setup
- Test execution with plain console output
- Automatic test summary

### `scripts/test-summary.sh`
Extracts and displays test results from XML files:
- Aggregates results from all test directories (debug/release variants)
- Shows total, passed, failed, errors, skipped counts
- Lists failed test classes
- Finds HTML reports in all possible locations
- Can be used standalone or called by `scripts/run-tests.sh`

### `scripts/check-device.sh`
Checks if an Android device or emulator is connected:
- Verifies `adb` is installed
- Lists connected devices
- Tests device connectivity
- Provides helpful error messages

### `scripts/run-instrumented-tests.sh`
Runs instrumented tests with device check:
- Verifies device is connected
- Runs tests with optional test class filter
- Shows test summary
- Displays report location

## Resources

- [JUnit 4 Documentation](https://junit.org/junit4/)
- [Mockito Documentation](https://site.mockito.org/)
- [Kotlin Coroutines Testing](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-test/)
- [Espresso Testing Guide](https://developer.android.com/training/testing/espresso)
- [Android Testing Guide](https://developer.android.com/training/testing)
