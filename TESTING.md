# Testing Guide

This document describes the testing setup, how to run tests, and how to interpret results for the TREC Custom Logsheets app.

## Overview

Unit tests are located in `app/src/test/` and run on the JVM without requiring an Android device or emulator. They are designed to be fast, deterministic, and CI-ready.

## Current Test Coverage

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

## Running Tests

### Recommended: Use Test Script (Matches CI)

The easiest way to run tests locally with the same setup as CI:

```bash
./run-tests.sh
```

This script:
- Runs tests the same way as CI (`./gradlew test --console=plain`)
- Shows a detailed summary with test counts
- Lists failed tests
- Provides links to HTML reports
- Exits with the same code as CI (0 = success, non-zero = failure)

### Direct Gradle Commands

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests "com.trec.customlogsheets.data.ConvertersTest"

# Run with verbose output
./gradlew test --info

# Run tests and generate coverage report
./gradlew test jacocoTestReport
```

### Quick Summary After Running Tests

```bash
# Run tests and get summary
./gradlew test && ./test-summary.sh

# Or just get summary if tests already ran
./test-summary.sh
```

## Viewing Test Results

### Command Line Output

The test output shows:
- `BUILD SUCCESSFUL` = All tests passed
- `BUILD FAILED` = Some tests failed
- Test summary at the end: `X tests completed, Y failed`

**Note about stack traces in passing tests:**
If you see stack traces in `STANDARD_ERROR` output for tests that are passing, this is **expected and harmless**. This happens because:

1. Some ViewModels (like `MainViewModel`) initialize by calling methods that access Android APIs (e.g., `DocumentFile`, file system operations)
2. In JVM unit tests, these Android APIs are not available, so they throw exceptions
3. These exceptions are **caught and handled gracefully** by the code (using try-catch blocks)
4. The tests still **pass correctly** because the exceptions don't affect the test logic
5. The stack traces are logged to stderr but don't indicate test failures

**What to look for:**
- ✅ **Test status**: If tests show "PASSED", they are working correctly
- ✅ **Test summary**: Check the final summary for total passed/failed counts
- ❌ **Only worry if**: Tests show "FAILED" status or the summary shows failures > 0

The test configuration is set to hide standard streams for passing tests to reduce noise, but they're still captured in XML reports for debugging if needed.

### HTML Report (Recommended)

After running tests, open the HTML report:
```
app/build/reports/tests/testDebugUnitTest/index.html
# or
app/build/reports/tests/testReleaseUnitTest/index.html
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
```

These are used by CI systems and IDEs. The `test-summary.sh` script aggregates results from all test result directories.

## CI Integration

### How CI Checks Test Results

**Yes, CI explicitly checks if tests passed!**

The GitHub Actions workflow (`.github/workflows/unit_tests.yml`):
1. Runs `./gradlew test --console=plain` - **This will fail the CI job if any test fails**
2. Shows a test summary using `test-summary.sh`
3. Uploads HTML reports as artifacts
4. Publishes test results to GitHub (using `publish-unit-test-result-action`)

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
   - Download "test-report" artifact to view HTML report

2. **Test Results Comment (if enabled):**
   - The `publish-unit-test-result-action` will post a comment on PRs
   - Shows pass/fail status for each test class
   - Clickable links to see details

### CI Configuration

The project uses GitHub Actions with:
- JDK 17 (Temurin distribution)
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

- **JUnit 4.13.2** - Test framework
- **Mockito 5.1.1** - Mocking framework
- **Mockito Kotlin 5.1.0** - Kotlin-friendly Mockito extensions
- **Kotlin Coroutines Test 1.7.3** - Testing coroutines
- **Turbine 1.0.0** - Testing Flow/StateFlow
- **AssertJ 3.24.2** - Fluent assertions (available for future use)
- **org.json:json:20231013** - JVM-compatible JSON library for unit tests

## Writing New Tests

1. **Create test files** in `app/src/test/java/com/trec/customlogsheets/` matching the source structure
2. **Use JUnit 4** annotations:
   - `@Test` - Marks a test method
   - `@Before` - Setup before each test
   - `@After` - Cleanup after each test
3. **Follow naming convention**: `ClassNameTest.kt`
4. **Keep tests**:
   - Fast (no network calls, no file I/O unless mocked)
   - Isolated (no shared state between tests)
   - Deterministic (same input always produces same output)
5. **Mock Android dependencies**:
   - Use Mockito to mock `Context`, `DocumentFile`, etc.
   - Don't use real Android classes in unit tests
   - Wrap `AppLogger` calls in try-catch if needed (Android Log not available in JVM tests)

### Example Test Structure

```kotlin
package com.trec.customlogsheets.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MyClassTest {
    private lateinit var subject: MyClass

    @Before
    fun setUp() {
        subject = MyClass()
    }

    @Test
    fun `test description`() {
        // Arrange
        val input = "test"
        
        // Act
        val result = subject.method(input)
        
        // Assert
        assertEquals("expected", result)
    }
}
```

## Troubleshooting

### Tests fail with "ClassNotFoundException"
- Ensure test dependencies are properly synced: `./gradlew --refresh-dependencies`

### Tests fail with Android class errors
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
- Use the test summary script: `./test-summary.sh`

### CI Shows "Success" but Tests Failed
This shouldn't happen with the current setup. If it does:
- Check that the workflow doesn't have `continue-on-error: true`
- Verify the workflow file is correct
- Check if tests are actually running (look for "Test Results:" in output)

### Test Results Directory Not Found
- Make sure tests have been run: `./gradlew test`
- Check for results in: `app/build/test-results/testDebugUnitTest/` or `app/build/test-results/testReleaseUnitTest/`
- The `test-summary.sh` script automatically checks all possible locations

## Helper Scripts

### `run-tests.sh`
Runs tests locally with the same setup as CI, including:
- JDK version check
- Execute permission setup
- Test execution with plain console output
- Automatic test summary

### `test-summary.sh`
Extracts and displays test results from XML files:
- Aggregates results from all test directories (debug/release variants)
- Shows total, passed, failed, errors, skipped counts
- Lists failed test classes
- Finds HTML reports in all possible locations
- Can be used standalone or called by `run-tests.sh`

## Resources

- [JUnit 4 Documentation](https://junit.org/junit4/)
- [Mockito Documentation](https://site.mockito.org/)
- [Kotlin Coroutines Testing](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-test/)
- [Android Testing Guide](https://developer.android.com/training/testing)
