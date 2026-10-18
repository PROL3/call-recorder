# Call Recorder Helper

An Android app for managing audio recordings on the device. It provides a searchable recording library, in-app playback, favorites, audio import, sharing, and a recoverable trash bin.

## Features

- Browse recordings created by the app and audio files imported from the device.
- Play and pause recordings in the app, seek through playback, and view elapsed and total time.
- Search recordings, mark favorites, rename files, and share audio with other apps.
- Move recordings to Trash and restore them. Items are permanently removed after 30 days; Android's scheduled job checks for expired items periodically.
- Configure microphone, phone-state, notification, accessibility, and battery-optimization access from the Settings screen.

## Requirements

- Android 10 (API 29) or newer.
- Android SDK platform 37 for building.
- A compatible JDK and Android SDK installation. The Gradle wrapper downloads Gradle 9.6.0 if it is not already installed.

## Build

From the repository root, build the debug APK with the Gradle wrapper:

```bash
# macOS / Linux
./gradlew assembleDebug

# Windows PowerShell
.\gradlew.bat assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Open the project in Android Studio to install it on a connected device or emulator.

## Permissions and setup

Open **Settings** in the app and grant each requested permission from the Android system dialog. The app uses:

- **Microphone** to record audio.
- **Phone state** to detect call state transitions.
- **Notifications** to show the foreground-service recording notification (Android 13+).
- **Accessibility service** to observe window-state events and keep the call-state helper service active. The service does not request access to window contents.
- **Ignore battery optimizations** as an optional setting that may help background reliability on some devices.

Accessibility must be enabled manually in Android Settings. On Android 13 and newer, sideloaded APKs may require allowing restricted settings from the app's App info page first. The exact labels and steps can vary by device manufacturer.

## Recording behavior and limitations

The helper detects call-state changes and attempts to record using Android's `MediaRecorder`. Android versions, device manufacturers, carrier apps, and privacy restrictions determine whether call audio is available. Many Android devices do not allow ordinary third-party apps to capture both sides of a phone call. Granting permissions does not guarantee that call audio can be recorded, and the app does not bypass Android's restrictions.

On recent Android versions, microphone foreground services are subject to while-in-use and background-start restrictions. Automatic recording may therefore be blocked by the operating system even when the microphone permission and accessibility service are enabled.

## Storage and retention

Recordings are stored in the app-specific external files directory under `Recordings/`. They are private to this app and are normally removed by Android when the app is uninstalled. Favorites and trash timestamps are stored in app preferences. Imported files are copied into the same recordings directory.

Moving a file to Trash does not immediately delete its audio data. It remains recoverable until restored, permanently deleted by the user, or removed after the 30-day retention period.
