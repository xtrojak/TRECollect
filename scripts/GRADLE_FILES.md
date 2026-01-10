# Gradle Files Organization

## Why Gradle Files Stay in Root

Gradle has specific requirements about where certain files must be located:

### Required in Root Directory

These files **must** stay in the project root:

1. **`build.gradle`** (root)
   - Project-level build configuration
   - Defines repositories, plugins, and subprojects
   - Gradle looks for this in the root by default

2. **`settings.gradle`**
   - Defines project structure and included modules
   - Gradle reads this first to understand the project layout
   - Must be in root

3. **`gradlew`** and **`gradlew.bat`**
   - Gradle wrapper scripts
   - Users expect these in root for standard `./gradlew` usage
   - Can technically be moved, but breaks standard conventions

4. **`gradle/` directory**
   - Contains wrapper properties (`gradle-wrapper.properties`)
   - Contains wrapper JAR (`gradle-wrapper.jar`)
   - Gradle wrapper expects this location

5. **`gradle.properties`**
   - Project-wide Gradle properties
   - Gradle reads this from root by default
   - Can be overridden with `-P` flags, but root is standard

### What Could Be Moved (But Usually Isn't)

1. **Build scripts** - Could theoretically be in a subdirectory, but:
   - Breaks standard conventions
   - Makes project harder for others to understand
   - Most tools expect standard layout

2. **Custom Gradle scripts** - If you had custom build scripts, they could go in `scripts/`, but the core files should stay in root.

## Standard Android Project Layout

```
project-root/
├── build.gradle          # ← Must stay
├── settings.gradle        # ← Must stay
├── gradle.properties     # ← Should stay (standard)
├── gradlew               # ← Should stay (standard)
├── gradlew.bat           # ← Should stay (standard)
├── gradle/               # ← Must stay
│   └── wrapper/
├── app/
│   └── build.gradle      # Module-level (can't move)
└── scripts/              # ← Your scripts (moved here)
    ├── run-tests.sh
    └── ...
```

## Best Practice

**Keep Gradle files in root** - This is the standard Android/Gradle convention that:
- Everyone expects
- Tools understand
- Documentation assumes
- CI/CD systems use

The root directory will always have some Gradle files - this is normal and expected for any Gradle project.

## Summary

- ✅ **Scripts moved to `scripts/`** - Cleaner root directory
- ✅ **Gradle files stay in root** - Required by Gradle and standard conventions
- ✅ **Result**: Better organization while maintaining compatibility
