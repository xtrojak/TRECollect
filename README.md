# TRECollect

[![Unit Tests](actions/workflows/unit_tests.yml/badge.svg)](actions/workflows/unit_tests.yml)

TRECollect is an Android application for collecting and managing field sampling metadata with custom logsheets. The app enables researchers to create sampling sites, fill out dynamic forms, capture GPS locations, take photos, and upload submissions to centralized storage for processing.

## Features

- **Team-Based Configuration**
  - Select sampling team (LSI or AML)
  - For LSI team, select subteam (Soil, Sediment, or Shoreline)
  - Team/subteam selection determines available forms and folder structure
  - Initial setup requires team selection before use

- **Sampling Site Management**
  - Create and manage ongoing and finished sampling sites
  - Organize sites in team/subteam-based folder hierarchy
  - Track site status and upload progress

- **Dynamic Form System**
  - JSON-configured custom logsheets organized by team/subteam
  - Team-specific form configurations:
    - LSI teams have distinct forms for Soil, Sediment, and Shoreline sampling
    - AML team has specialized forms for contaminated site assessment
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
  - Team/subteam-based folder organization:
    - Local: `TREC_logsheets/{team}/{subteam}/{ongoing,finished,deleted}/`
    - ownCloud: `{UUID}/{team}/{subteam}/{siteName}/`

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
   git clone <repository-url>
   cd TRECollect
   ```

2. Open the project in Android Studio

3. Sync Gradle files and wait for dependencies to download

4. Build the project:
   ```bash
   ./gradlew build
   ```

### Configuration

**Initial Setup (Required on First Launch):**

1. **Select Sampling Team**: Choose your team (LSI or AML)
   - If LSI is selected, choose subteam (Soil, Sediment, or Shoreline)
   - This determines which forms are available and the folder structure

2. **Select Output Folder**: Choose where submissions will be stored
   - The app will create the folder structure: `TREC_logsheets/{team}/{subteam}/`
   - You can select a Google Drive folder if linked to your device

3. **Optional - ownCloud Settings**: Configure in `app/src/main/res/values/strings.xml`:
   - `owncloud_url`: Your ownCloud WebDAV endpoint URL
   - `owncloud_access_token`: Your ownCloud access token
   - Or configure through the app's Settings activity

**Note**: The app requires team selection and output folder configuration before you can create sites.

## Testing

The project includes comprehensive unit tests and instrumented tests. See [TESTING.md](TESTING.md) for detailed information.

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

See the [test summary](actions) for the latest test results.

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
│   │   └── assets/             # Form configurations organized by team
│   │       ├── teams/
│   │       │   ├── LSI/
│   │       │   │   ├── Soil/forms_config.json
│   │       │   │   ├── Sediment/forms_config.json
│   │       │   │   └── Shoreline/forms_config.json
│   │       │   └── AML/forms_config.json
│   │       └── forms_config.json  # Fallback (backwards compatibility)
│   └── test/                   # Unit tests
├── build.gradle
└── proguard-rules.pro
```

### Folder Structure

**Local Storage:**
```
TREC_logsheets/
├── LSI/
│   ├── Soil/
│   │   ├── ongoing/
│   │   ├── finished/
│   │   └── deleted/
│   ├── Sediment/
│   │   ├── ongoing/
│   │   ├── finished/
│   │   └── deleted/
│   └── Shoreline/
│       ├── ongoing/
│       ├── finished/
│       └── deleted/
└── AML/
    ├── ongoing/
    ├── finished/
    └── deleted/
```

**ownCloud Storage:**
```
{UUID}/
├── LSI/
│   ├── Soil/
│   │   └── {siteName}/
│   ├── Sediment/
│   │   └── {siteName}/
│   └── Shoreline/
│       └── {siteName}/
└── AML/
    └── {siteName}/
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

### Building Release APK

To build a signed release APK for distribution, see the [Building the Project](#building-the-project) section. For testing, debug APKs are automatically built on pull requests to `main` and are available as workflow artifacts.

### Contributing

1. Create a feature branch from `main` or `develop`
2. Make your changes
3. Ensure all tests pass: `./scripts/run-tests.sh`
4. Submit a pull request
   - Debug APK will be automatically built and available as an artifact
   - Download from the workflow run page (available for 30 days)

## License

[Add your license information here]

## Acknowledgments

- TREC research organization
- OpenStreetMap contributors
- Android Open Source Project

## Support

For issues, questions, or contributions, please open an issue on GitHub.
