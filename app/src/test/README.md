# Unit Tests

This directory contains unit tests for the TRECollect app. These tests run on the JVM (not requiring an Android device/emulator) and are designed to be CI-ready.

## Running Tests

### Run all unit tests
```bash
./gradlew test
```

### Run specific test class
```bash
./gradlew test --tests "com.trec.customlogsheets.data.ConvertersTest"
```

### Run tests with coverage
```bash
./gradlew test jacocoTestReport
```

### View test results
After running tests, view the HTML report at:
```
app/build/reports/tests/test/index.html
```

## Test Structure

Tests are organized to match the main source structure:
- `data/` - Tests for data classes, converters, and data models
- `util/` - Tests for utility classes
- `ui/` - Tests for ViewModels and UI logic (with mocked dependencies)

## CI Integration

These tests are designed to run in CI pipelines. They:
- Run on JVM (no Android runtime required)
- Are fast and deterministic
- Don't require external dependencies or network access
- Use proper mocking for Android-specific components

### Example CI Configuration

#### GitHub Actions
```yaml
- name: Run unit tests
  run: ./gradlew test
```

#### GitLab CI
```yaml
test:
  script:
    - ./gradlew test
```

## Writing New Tests

1. Create test files in the same package structure as source files
2. Use JUnit 4 annotations (`@Test`, `@Before`, etc.)
3. Follow naming convention: `ClassNameTest.kt`
4. Keep tests fast, isolated, and deterministic
5. Mock Android dependencies (Context, DocumentFile, etc.)

## Test Coverage Goals

- Aim for 70-80% code coverage
- Focus on business logic and data transformations
- Critical paths: site creation, upload logic, data conversions
