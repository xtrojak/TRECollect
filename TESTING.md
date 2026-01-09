# Testing Guide

This document describes the testing setup for the TREC Custom Logsheets app.

## Unit Tests

Unit tests are located in `app/src/test/` and run on the JVM without requiring an Android device or emulator. They are designed to be fast, deterministic, and CI-ready.

### Current Test Coverage

#### Data Layer Tests
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

### Running Tests

#### Local Development
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

#### View Test Results
After running tests, view the HTML report:
```
app/build/reports/tests/test/index.html
```

### CI Integration

The tests are designed to run in CI pipelines. Example configurations are provided:

- **GitHub Actions**: See `.github/workflows/tests.yml.example`
- **GitLab CI**: Add to `.gitlab-ci.yml`:
  ```yaml
  test:
    script:
      - ./gradlew test
  ```

### Test Dependencies

The following testing libraries are included:

- **JUnit 4.13.2** - Test framework
- **Mockito 5.1.1** - Mocking framework
- **Mockito Kotlin 5.1.0** - Kotlin-friendly Mockito extensions
- **Kotlin Coroutines Test 1.7.3** - Testing coroutines
- **Turbine 1.0.0** - Testing Flow/StateFlow
- **AssertJ 3.24.2** - Fluent assertions (available for future use)

### Writing New Tests

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

### Future Test Additions

Potential areas for additional unit tests:

- **ViewModels** (with mocked dependencies)
  - MainViewModel site creation logic
  - Upload status management
  - Site finalization flow

- **Utility Functions**
  - Folder path generation
  - Timestamp formatting
  - Data validation

- **Business Logic**
  - Upload retry logic (with mocked network)
  - Form validation rules
  - Data transformation functions

### Test Coverage Goals

- **Current**: ~15-20% (foundational tests)
- **Target**: 70-80% coverage
- **Focus Areas**:
  - Business logic (ViewModels, Managers)
  - Data transformations (Converters, FormData)
  - Critical paths (site creation, upload, finalization)

### Troubleshooting

#### Tests fail with "ClassNotFoundException"
- Ensure test dependencies are properly synced: `./gradlew --refresh-dependencies`

#### Tests fail with Android class errors
- Make sure you're running unit tests (`./gradlew test`), not instrumented tests
- Mock Android dependencies instead of using real classes

#### Tests are slow
- Check for network calls or file I/O - these should be mocked
- Ensure tests are running in parallel (default Gradle behavior)

### Resources

- [JUnit 4 Documentation](https://junit.org/junit4/)
- [Mockito Documentation](https://site.mockito.org/)
- [Kotlin Coroutines Testing](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-test/)
- [Android Testing Guide](https://developer.android.com/training/testing)
