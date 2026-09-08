# EnmaPatcher

An Android app that patches Yo-kai Watch 1 Smartphone (`jp.co.level5.yws1`) by downloading replacement files from configurable GitHub repositories or local ZIPs and applying them to the installed APK.

## Features

- Multiple mods at once, with priority order (top overrides bottom)
- GitHub repos and local `.zip` imports, with file preview
- Per-file downloads that support repos over 300 MB
- Remote blocklist checked on every patch (banned repos, words, paths)
- Save exporter/importer (`head.yw` + `game0-2.yw`, `main.bin` excluded, auto-backup)
- Manual or automatic target app selection
- Configurable app name (mod `appName` or your own)
- Mod badges: platforms, license, AI content, recommended version, health
- Six languages with dynamic locale switching
- Dark theme

## Requirements

- Android 7.0 (API 24) or higher
- The target game installed on the device
- Internet access to download patches

## Usage

1. Install the app.
2. Open it. Pick the target app automatically or manually. If it is detected, the Patch button becomes active.
3. Optionally go to Settings to manage mods, change the language, or set a custom app name.
4. Tap Patch. The app checks the blocklist, downloads the mods, validates them, applies them, and signs the result.
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
  "appName": "My Patch Name",
  "exclude": ["assets/data/mov"],
  "recommended_version": "1.0.13"
}
```

See the [Patch Repo wiki](https://github.com/hxgohxrr/Enma-Patcher/wiki/Patch-Repo) for the full config reference (include/exclude filters, platforms, versions, mod compatibility, license, AI content).

## Building from source

### Prerequisites

- JDK 17 or higher
- Android SDK with API 34

### Setup

1. Clone the repository.

2. Create a `.env` file at the project root for release signing:

```
KEYSTORE_PASSWORD=your_password
KEY_PASSWORD=your_password
KEY_ALIAS=your_alias
```

3. Place the corresponding `keystore.jks` at the project root.

### Build

```
# Debug
./gradlew assembleDebug

# Release (signed)
./gradlew assembleRelease
```

Output is in `app/build/outputs/apk/`.

## Architecture

| Component | Description |
|---|---|
| `MainViewModel` | Holds app state, coordinates the patch pipeline |
| `EnmaPatcherEngine` | Runs the patch steps in sequence |
| `GithubPatchSource` | Downloads config and patch files from GitHub or local ZIPs |
| `ModPolicyChecker` | Enforces the remote and built-in blocklists |
| `SaveManager` | Save discovery, export and import |
| `ApkBundleProcessor` | Locates the installed game APK via PackageManager |
| `ApkPatcher` | Merges patch files into the APK via ZIP replacement |
| `ApkSigner` | Signs the output APK (APK Signature Scheme v2) |

## License

MIT License. See [LICENSE](LICENSE).

This project is not affiliated with Level-5 Inc.
