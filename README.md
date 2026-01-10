# TRECollect

[![Unit Tests](https://github.com/xtrojak/TREC-custom-logsheets-app/actions/workflows/unit_tests.yml/badge.svg)](https://github.com/xtrojak/TREC-custom-logsheets-app/actions/workflows/unit_tests.yml)

TRECollect is an Android application for collecting and managing field sampling metadata with custom logsheets. The app enables researchers to create sampling sites, fill out dynamic forms, capture GPS locations, take photos, and upload submissions to centralized storage for processing.

## Features

- **Sampling Site Management**
  - Create and manage ongoing and finished sampling sites
  - Organize sites in a structured folder hierarchy
  - Track site status and upload progress

- **Dynamic Form System**
  - JSON-configured custom logsheets
  - Support for multiple field types:
    - Text, number, and date inputs
    - Multi-select options
    - GPS location picker with map integration
    - Photo capture
    - Table fields with rows and columns
    - Dynamic fields with sub-fields
    - Section grouping
  - Draft and submitted form states
  - Form data persistence

- **Offline Capabilities**
  - Offline map support using OpenStreetMap
  - Download map regions for offline GPS usage
  - Automatic cleanup of expired map regions

- **Data Synchronization**
  - Automatic upload to ownCloud on site completion
  - Manual upload and re-upload capabilities
  - Batch upload for multiple sites
  - Upload status tracking with visual indicators

- **Data Management**
  - Room database for local persistence
  - XML-based form data storage
  - Structured folder organization (ongoing/finished/deleted)

## Requirements

- Android 7.0 (API level 24) or higher
- Internet connection for ownCloud uploads
- Storage access for saving form data and offline maps

## Building the Project

### Prerequisites

- Android Studio (latest stable version)
- JDK 17 or higher
- Android SDK with API level 34

### Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/xtrojak/TREC-custom-logsheets-app.git
   cd TREC-custom-logsheets-app
   ```

2. Open the project in Android Studio

3. Sync Gradle files and wait for dependencies to download

4. Build the project:
   ```bash
   ./gradlew build
   ```

### Configuration

Before running the app, configure the following in `app/src/main/res/values/strings.xml`:

- `owncloud_url`: Your ownCloud WebDAV endpoint URL
- `owncloud_access_token`: Your ownCloud access token

Alternatively, configure these settings through the app's Settings activity after first launch.

## Testing

The project includes comprehensive unit tests and instrumented tests. See [TESTING.md](TESTING.md) and [INSTRUMENTED_TESTING.md](INSTRUMENTED_TESTING.md) for detailed information.

### Running Unit Tests Locally

```bash
# Run all unit tests (recommended)
./scripts/run-tests.sh

# Or use Gradle directly
./gradlew test

# View test summary
./scripts/test-summary.sh
```

### Running Instrumented Tests Locally

Instrumented tests require an Android device or emulator to be connected.

```bash
# Check if device/emulator is connected
./scripts/check-device.sh

# Run all instrumented tests
./scripts/run-instrumented-tests.sh

# Or use Gradle directly
./gradlew connectedAndroidTest

# Run specific test class
./scripts/run-instrumented-tests.sh "com.trec.customlogsheets.database.SamplingSiteDaoTest"
```

### Test Coverage

**Unit Tests:**
- Data layer (TypeConverters, data classes, form configuration)
- Business logic (ViewModel factories, utility functions)
- Form data structures and validation

**Instrumented Tests:**
- Database operations (Room DAOs, migrations, TypeConverters)
- Database CRUD operations with real SQLite

See the [test summary](https://github.com/xtrojak/TREC-custom-logsheets-app/actions) for the latest test results.

## Project Structure

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/trec/customlogsheets/
│   │   │   ├── data/          # Data models, database, file management
│   │   │   ├── ui/             # Activities, ViewModels, adapters
│   │   │   └── util/           # Utilities (logging, etc.)
│   │   ├── res/                # Resources (layouts, strings, etc.)
│   │   └── assets/             # Form configuration JSON
│   └── test/                   # Unit tests
├── build.gradle
└── proguard-rules.pro
```

## Key Technologies

- **Kotlin** - Primary programming language
- **Android Jetpack**
  - Room - Local database
  - ViewModel & LiveData/StateFlow - UI state management
  - Material Components - UI framework
- **Coroutines** - Asynchronous operations
- **OkHttp** - HTTP client for ownCloud WebDAV API
- **OpenStreetMap** - Offline mapping support

## Development

### Code Style

- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Add KDoc comments for public APIs
- Keep functions focused and single-purpose

### Contributing

1. Create a feature branch from `main` or `develop`
2. Make your changes
3. Ensure all tests pass: `./scripts/run-tests.sh`
4. Submit a pull request

## License

[Add your license information here]

## Acknowledgments

- TREC research organization
- OpenStreetMap contributors
- Android Open Source Project

## Support

For issues, questions, or contributions, please open an issue on GitHub.
