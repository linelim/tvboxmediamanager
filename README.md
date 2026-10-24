# TV Box Media Manager (Android TV standalone project)

A native Android application project with a WebView-based TV-friendly interface and an embedded Kotlin backend. It does not require Termux, Docker, or a separate server.

## Features in this MVP
- Android Storage Access Framework folder picker with persistent read/write access to a selected USB/library directory.
- Recursive video scan for common formats (up to 10,000 entries per scan).
- Metadata search using TMDb, OMDb, and TVmaze (TVmaze is TV-only and does not require a key).
- Writes Kodi-style `.nfo` sidecars and downloads poster art next to the selected video.
- Optional video rename with a confirmation prompt; rename is never automatic.
- Reuses/overwrites an existing same-name NFO/poster only after the user confirms the save action.
- No account or LAN server is exposed. Provider API requests are made by the app over HTTPS.

## Build

Requirements: Android Studio with Android SDK Platform 35, JDK 17, and internet access for Gradle dependencies.

1. Open this folder (`TVBoxMediaManager-Android`) in Android Studio.
2. Allow Gradle sync to download Android Gradle Plugin 8.7.3, Kotlin plugin 2.0.21, and Gradle 8.9.
3. Select **Build > Build APK(s)**.
4. The debug APK should be at `app/build/outputs/apk/debug/app-debug.apk`.

Command line from a machine with Android SDK installed:

```sh
gradle assembleDebug
```

If the Gradle wrapper scripts are missing, open in Android Studio or generate a wrapper for Gradle 8.9. (The wrapper properties are included; wrapper JAR/scripts are not bundled.)

## Install

Copy `app/build/outputs/apk/debug/app-debug.apk` to the TV box and open it with a file manager, allowing installation from that source if Android asks. Alternatively, use `adb install -r app-debug.apk` from a computer with ADB.

## First run

1. Open the app on the TV box.
2. Choose the USB/library folder using Android's system folder picker and grant access.
3. Press **Scan library**.
4. Select a video, choose Movie or TV show, search a provider, select the correct result, and review the overview.
5. Press **Save NFO + poster** and confirm.
6. Enter your own API keys in Settings if you want TMDb or OMDb search. TVmaze requires no key.

## Safety and limitations

- Start by testing on a small sample folder and keep a backup of important files.
- The app does not automatically rename or move video files. Rename is an explicit separate action and Android's storage provider must support renaming.
- Saving creates/updates sidecar NFO and poster files beside the video. A same-name sidecar can be overwritten after the confirmation dialog.
- Android TV vendors vary in how their USB document provider behaves. Some may not expose the drive in the picker or may limit rename/write operations; this depends on the device firmware.
- The scan is capped at 10,000 visited entries to avoid an accidental unbounded scan on a TV box.
- This is an MVP: no full TV episode/season matching, bulk scrape, advanced naming templates, fanart management, or undo history yet.
- API keys are stored in local WebView storage. Do not install the app on an untrusted device.

## Providers
- TMDb: requires an API key supplied by the user.
- OMDb: requires an API key supplied by the user.
- TVmaze: TV search without a key.

## Build without installing Android Studio locally

A GitHub Actions workflow is included at `.github/workflows/build-apk.yml`. Push this project to a GitHub repository, then open **Actions → Build Android APK → Run workflow**. When it finishes, download the `tvbox-media-manager-debug-apk` artifact from that run. GitHub-hosted runners provide the Android SDK and build tools; this environment does not.
