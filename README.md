# TV Box Media Manager (Android TV standalone project)

A native Android app with a WebView-based TV-friendly interface and an embedded Kotlin backend. No Termux, Docker, or separate server. Version 0.5.0 (minimum Android 7.0).

## Features

**Library and scanning**
- Android Storage Access Framework folder picker with persistent access to a USB or library folder.
- Recursive video scan for common formats (up to 10,000 entries per scan).
- Filename parsing: extracts title, year, and season/episode (`S01E02`, `1x02`) and strips release tags such as resolution, codec, and source.
- Views for all files, with or without metadata, duplicates, movies, TV episodes, and sorting by name, year, or recent match.

**Metadata**
- Existing NFO files are read. If one contains TMDb, IMDb, or TVmaze IDs, the file is matched directly, with no search. Bulk matching uses these IDs first.
- Multi-episode files are supported: `S01E01E02`, `S01E01-E02`, and `S01E01-02`. Titles are combined, and the NFO contains one block per episode.
- Blu-ray (`BDMV`) and DVD (`VIDEO_TS`) folders are treated as single movies. ISO files are supported too. The disc's NFO, poster, and fanart go inside the folder as `movie.nfo`, `poster.jpg`, and `fanart.jpg`.
- Providers: TMDb, OMDb, and TVmaze (TVmaze needs no key; TV only).
- Movies: title, original title, year, plot, runtime, rating, genres, cast, IMDb/TMDb IDs.
- TV episodes: show-level and per-episode data, with season and episode numbers entered or parsed from the filename.
- Bulk auto-match: matches only confident title and year matches. Everything else goes to a "Needs review" list with candidate results.

**Writing to the library (all Kodi-compatible)**
- Movies: `<name>.nfo`, `<name>-poster.jpg`, `<name>-fanart.jpg`.
- Episodes: `<name>.nfo`, `<name>-thumb.jpg`.
- Shows (in the show folder, found automatically from a `Season N` subfolder): `tvshow.nfo`, `poster.jpg`, `fanart.jpg`, and `seasonNN-poster.jpg`.

**Renaming and folders**
- Template renaming with a live preview, for example `{show} - S{season}E{episode} - {episode_title}`. Empty parts are dropped and illegal characters are replaced.
- Renaming the video also renames matching companion files (NFO, posters, subtitles) in the same folder.
- Movie sets: TMDb collection names are written as `<set>` and can be used as a `{set}` folder token, for example `{set}/{title} ({year})`. The library has a set filter.
- Optional folder templates move files into folders, as tinyMediaManager does. Examples: movies as `{title} ({year})/{title} ({year})`, episodes as `{show}/Season {season}/{show} - S{season}E{episode} - {episode_title}`. Folders are created as needed and matched to existing ones by name.
- Bulk **Organize into folders** previews every planned move (`from → to`) before anything changes, then applies them with one confirmation. Episodes update the show's `tvshow.nfo` and artwork in their new folder.
- Moves use the storage provider's native move where it's supported. Otherwise the file is copied, the copy's size is checked, and only then is the original removed. On USB drives without native move this can be slow for large films.
- Rename is never automatic and always confirmed.

**Safety and undo**
- Every write, overwrite, rename, move, and export is logged. "Undo last change" in Settings reverses the most recent batch: renames and moves go back, created folders and files are deleted, and overwritten files are restored from a backup.
- The app keeps the last 300 changes. Backups are stored in the app's private storage.

**Export**
- Exports matched files to a CSV (`tvmm-export-<date>.csv`) in the library folder.

## Build

Requirements: Android Studio with Android SDK Platform 35, JDK 17, and internet access for Gradle dependencies.

1. Open this folder in Android Studio and allow Gradle sync.
2. Select **Build > Build APK(s)**, or run `gradle assembleDebug`.
3. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

To build without local tooling, push to GitHub and run the **Build Android APK** workflow. Download the `tvbox-media-manager-v0.3.0-debug-apk` artifact.

## Install

Copy the APK to the TV box and open it with a file manager, allowing installation from that source. Or use `adb install -r app-debug.apk`.

## First run

1. Choose the library folder using Android's folder picker and grant access.
2. Press **Scan library**.
3. Enter TMDb and/or OMDb keys in Settings (TVmaze needs none).
4. Either match a file by hand (**Find metadata**), or use **Auto-match unmatched** in the Bulk tools panel. Review the confirmation before anything is written.

## Limitations

- Scanning only reads files. Writing creates or overwrites sidecar files beside each video. Same-name sidecars are overwritten after confirmation, but the previous version is kept for undo.
- Storage providers vary. Some USB providers do not support rename or write; the app reports this rather than failing silently.
- Not implemented: subtitle downloads and trailers, episode-level missing-episode lists, and fanart.tv artwork.
- Source folders are not removed when they become empty after a move. Remove them manually if you no longer need them.
- Filename parsing is heuristic. Unusual names may need manual correction before saving; bulk auto-match is designed to leave these for review rather than guess.
- API keys are stored in the app's local WebView storage. Do not install the app on an untrusted device.
- Provider availability, terms, and rate limits apply.

## Changes in 0.5.0

- Reads existing NFO files and matches by the IDs they contain.
- Multi-episode files (`S01E01E02`, `S01E01-E02`).
- Movie sets from TMDb, with a `{set}` folder token and a set filter.
- Blu-ray, DVD, and ISO support.
- Notes: episode numbers in the editor accept a range like `1-2`. Movie sets only appear for movies matched in this version; re-match older ones to pick them up.

## Changes in 0.4.0

- Added folder templates: moving and renaming in one step, from the single-file panel or in bulk.
- Added bulk "Organize into folders" with a full preview of planned moves.
- Moves are undoable, including folders the app created.
- Scanning now records each file's folder, so previews show exactly where a file is moving from.

## Changes in 0.3.0

- Added TV episode matching, episode NFOs, and show-level artwork.
- Added filename parsing and template-based renaming with companion files.
- Added richer NFO data and artwork (genres, runtime, rating, IDs, cast, fanart).
- Added bulk auto-match with a review queue, duplicate detection, and CSV export.
- Added undo for all writes, renames, and exports.
- Fixed: confirmation and rename prompts never appeared in 0.2.0. WebView's default confirm and prompt return false without showing a dialog, so saves and renames were silently cancelled. The app now shows native dialogs.
- Minimum Android version raised from 6.0 to 7.0 (undo uses an API introduced in 7.0).
