# EnmaPatcher

An Android app that patches Yo-kai Watch 1 Smartphone (`jp.co.level5.yws1`) by downloading replacement files from a configurable GitHub repository and applying them to the installed APK.
The default translation is made by the Project Make a Dream Team

## Features

- Detects the installed game automatically via PackageManager
- Downloads patch files from a configurable GitHub repository and branch
- Applies patches via ZIP entry replacement (no root required)
- Signs the output APK with a generated v1 JAR signature
- Outputs a ready-to-install patched APK
- Supports Spanish and English, with manual language switching
- Dark theme

## Requirements

- Android 7.0 (API 24) or higher
- Yo-kai Watch 1 Smartphone (`jp.co.level5.yws1`) installed on the device
- Internet access to download patches

## Usage

1. Install the app.
2. Open it. If the game is detected, the Patch button becomes active.
3. Optionally go to Settings to change the patch repository, branch, or language.
4. Tap Patch. The app downloads the patch files, applies them, and signs the result.
5. When done, tap Install APK or Share APK.

## Patch repository format

The app downloads files from a GitHub repository and replaces matching entries inside the game APK by path. The repository structure must mirror the internal APK paths:

```
assets/
  some/path/file.bin
res/
  raw/somefile.dat
```

The app also looks for an optional `enmapatcher.cfg.json` at the repository root:

```json
{
  "appName": "My Patch Name"
}
```

Only `appName` is read. Any other fields are ignored.

## Building from source

### Prerequisites

- JDK 17 or higher
- Android SDK with API 34
- A GitHub Personal Access Token with `read:packages` scope (for the ReVanced dependency)

### Setup

1. Clone the repository.

2. Add your GitHub Packages credentials to `~/.gradle/gradle.properties`:

```
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_TOKEN
```

3. Create a `.env` file at the project root for release signing:

```
KEYSTORE_PASSWORD=your_password
KEY_PASSWORD=your_password
KEY_ALIAS=your_alias
```

4. Place the corresponding `keystore.jks` at the project root.

### Build

```
# Debug
./gradlew assembleDebug

# Release (signed)
./gradlew assembleRelease
```

Output is in `app/build/outputs/apk/`.

## Translations
Spanish - hxgohxrr 

English - hxgohxrr

German - kxmal_47

## Architecture

| Component | Description |
|---|---|
| `MainViewModel` | Holds app state, coordinates the patch pipeline |
| `EnmaPatcherEngine` | Runs the patch steps in sequence |
| `GithubPatchSource` | Downloads config and patch files from GitHub |
| `ApkBundleProcessor` | Locates the installed game APK via PackageManager |
| `ApkPatcher` | Merges patch files into the APK via ZIP replacement |
| `ApkSigner` | Signs the output APK using BouncyCastle v1 JAR signing |

## License

This project is not affiliated with Level-5 Inc.
