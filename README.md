# Music Player

<img width="1080" height="2400" alt="Screenshot_2026-09-25-18-55-14-54_04e6797df9ab5245a25ec651125373b0" src="https://github.com/user-attachments/assets/d6920483-e916-4661-b95d-1eef317c7ff4" /><img width="1080" height="2400" alt="Screenshot_2026-09-25-18-55-28-87_04e6797df9ab5245a25ec651125373b0" src="https://github.com/user-attachments/assets/8b95e46f-14a8-4997-a53e-34ff9bf8d356" />

Disclaimer: the code in its entirety was written by an LLM, use at your own discretion.

A local-files music player for Android, written in Kotlin with Jetpack Compose and Media3.

## Features

- **Local playback** of MP3, FLAC and WebM (Opus/Vorbis) files.
- **One music folder**, chosen through the system folder picker. Every subfolder that contains
  audio files is shown as an album (nested folders work too); files placed directly in the root
  folder form an album named after it.
- **Album covers** taken from the art embedded in the tracks (ID3 `APIC`, FLAC `PICTURE`,
  Matroska attachments), falling back to a `cover.jpg` / `folder.jpg` / `front.jpg` in the folder.
- **Three tabs** along the bottom: **Library**, **Playlists** and **Player**, plus a mini-player
  above the tab bar while something is playing. Swipe left or right anywhere to move between
  the tabs; each tab remembers the album or playlist you had open.
- **Search** in the library (albums and songs), inside an album, and in and across playlists.
  Matching ignores case and accents.
- **Playlists**: create as many as you like. Long-press a song or an album anywhere in the library
  and choose *Add to playlist…*. Inside a playlist, long-press a song to move it up or down or
  remove it.
- **Player**: cover art, seek bar, previous / play-pause / next, shuffle and repeat. The queue is
  the active album or playlist, and the screen shows which one it is playing from.
- **Lyrics** shown on top of a blurred copy of the cover. Synced (LRC) lyrics highlight and follow
  the current line; tap a line to jump to it. Lyrics are read from, in order of preference:
  a sidecar `.lrc` file with the same name as the track, ID3 `SYLT`/`USLT`, or a
  `LYRICS`/`UNSYNCEDLYRICS` tag (FLAC and WebM).
- Background playback with a media notification, lock-screen and headset controls, and the queue
  is remembered across restarts.

The palette is dark grey with a yellowish-orange accent (`#E1A34F`).

## Getting the APK

Every push is built by GitHub Actions (see `.github/workflows/build.yml`). Open the latest
*Build* run under the repository's **Actions** tab and download the `music-player-apk` artifact.
It contains:

- `app-release.apk` — minified and optimised; this is the one to install.
- `app-debug.apk` — unminified, for debugging.

Both are signed with the shared key in `keystore/`, so a newer build installs over an older one
without losing your playlists. That key is public; if you want a private signing key, set the
`RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` environment variables when building.

Install with `adb` (on Arch Linux: `sudo pacman -S android-tools`):

```sh
adb install -r app-release.apk
```

## Building locally (Arch Linux)

You need JDK 17 and the Android SDK (platform 35):

```sh
sudo pacman -S jdk17-openjdk
# Either install Android Studio (AUR: android-studio) and let it fetch the SDK,
# or install the command-line SDK from the AUR (android-sdk-cmdline-tools-latest), then:
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

Point Gradle at the SDK and build:

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # adjust to your SDK location
./gradlew testDebugUnitTest assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

## Project layout

| Package | Contents |
| --- | --- |
| `tags` | Dependency-free readers for ID3v1/v2, FLAC metadata blocks, Matroska/WebM (EBML) and LRC, with unit tests in `app/src/test`. |
| `data` | Room database, the Storage Access Framework folder scanner and the repository. |
| `playback` | The Media3 `MediaSessionService`, queue persistence, and the controller wrapper the UI talks to. |
| `ui` | Compose screens: library, album, playlists, playlist and player. |

Requirements: Android 8.0 (API 26) or newer.
