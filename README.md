# DroidTerm

Android terminal with built-in adb + fastboot, Wireless debugging pairing, USB OTG adb/fastboot, SSH with
saved credentials and keys, clipboard task checklists and command bookmarks. See PLAN.md for the design.

## Install

Download `DroidTerm-<version>.apk` from the [latest release](https://github.com/JeremyDoege89/DroidTerm/releases/latest)
and open it on the phone. Android will ask you to allow installs from your browser or Files app. Android 11 or newer,
arm64 phones (and x86_64 emulators).

## Build

Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (arm64-v8a + x86_64, Android 11+).
Tests: `./gradlew :app:testDebugUnitTest`.

## Third-party code
- `terminal-emulator/`, `terminal-view/`: from termux-app v0.118.1, Apache License 2.0, see `LICENSE-APACHE-2.0.txt` (originally
  jackpal/Android-Terminal-Emulator). Modified: `TerminalSession` can be backed by a byte stream.
- `app/src/main/jniLibs/*/libadb.so`, `libfastboot.so`: static builds of Android platform-tools 35.0.2
  (lzhiyong/android-sdk-tools), Apache License 2.0, stripped.
- JSch (mwiede fork, BSD-style) and Bouncy Castle (MIT-style) via Maven.
