# DroidTerm — full-featured Android terminal (dev.dreamz.droidterm)

## Stack (defaults picked)
- Kotlin, Jetpack Compose (Material 3) for all screens; terminal surface is an Android View hosted in Compose.
- Terminal engine: vendored Termux `terminal-emulator` + `terminal-view` (Apache-2.0, from Android-Terminal-Emulator),
  with its small C PTY helper built by the NDK. Session class extended so a terminal tab can be backed by
  either a local PTY process or a byte stream (SSH channel, USB ADB shell).
- Room for task lists, bookmarks, SSH hosts. Secrets (passwords, private keys, passphrases) encrypted with an
  AES-GCM key held in Android Keystore; only ciphertext touches Room.
- SSH: JSch (mwiede fork) + BouncyCastle for ed25519.
- Toolchain: AGP 9.4.1 / Kotlin 2.4.20 / Gradle 9.8 (same as Adb5555), NDK 29, minSdk 30 (wireless debugging
  needs Android 11), targetSdk 36, arm64-v8a.

## Features
1. **Terminal**: multiple tabs, real PTY running /system/bin/sh with HOME, PATH (bundled tools first), TMPDIR set.
   Pinch zoom, copy/paste, scrollback, foreground service keeps sessions alive.
2. **Extra-keys row**: ESC, TAB, CTRL, ALT (sticky modifiers), ↑ ↓ ← → (history/cursor), HOME, END, PGUP, PGDN,
   |, -, /, ~, plus a bookmarks button and a paste button.
3. **Bundled adb + fastboot** (static aarch64 builds of platform-tools 35.0.2) shipped as `libadb.so`/`libfastboot.so`
   in jniLibs (only place Android 10+ allows exec), symlinked into `$PREFIX/bin` so `adb`/`fastboot` just work.
4. **ADB screen**:
   - Local device: discovers Wireless debugging pairing/connect ports with mDNS (NsdManager); pairing code entered
     from a notification (so the Settings pairing dialog stays open), runs `adb pair`, then `adb connect`.
   - Network devices: manual host:port pair/connect, saved devices list, one tap opens `adb -s X shell` tab.
   - Starts/stops the on-device adb server (`adb start-server`).
5. **USB OTG** (no root): UsbManager permission flow; Kotlin ADB-over-USB protocol (CNXN/AUTH with the same adbkey
   as the binary/OPEN shell) opening a shell tab, and a Kotlin fastboot-over-USB client (getvar, devices, reboot,
   reboot bootloader, flash partition from file, erase, oem/flashing commands, raw command).
6. **SSH**: saved hosts (host, port, user, password or key), key manager (generate ed25519/RSA, import PEM/OpenSSH,
   copy public key), host-key trust on first use, opens SSH session in a terminal tab.
7. **Clipboard task importer**: paste/import from clipboard, splits into commands (strips `$ `/`# ` prompts, joins
   `\` continuations, skips blank/comment lines, optionally drops prose), you name the task. Each command row:
   tap to check off (greys out), long-press/run button sends it to the active terminal. Progress persisted.
8. **Bookmarks**: saved commands with labels, run or insert into the terminal from a sheet.

## Milestones
M1 scaffold + vendored terminal + PTY tabs + extra keys · M2 bundled adb/fastboot + ADB screen + pairing
M3 USB ADB + fastboot · M4 SSH + keystore secrets · M5 tasks + bookmarks · M6 debug APK, polish
