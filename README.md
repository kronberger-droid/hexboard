<h1 align="center"><img src="docs/wordmark.svg" width="420" alt="Hexboard"></h1>

A hexagonal keyboard for Android. Swipe a key for capitals and extras,
drag sideways to delete, select or move the cursor, and make the layout
your own.

<p align="center">
  <img src="docs/screenshots/dark.png" width="45%" alt="Hexboard in the dark theme">
  <img src="docs/screenshots/light.png" width="45%" alt="Hexboard in the light theme">
</p>

## Features

- **Swipes.** Up for a capital, and diagonals on the punctuation keys for
  quotes, brackets and more.
- **Long press.** `a o u s` give `ä ö ü ß`, shown in the text while you
  hold. Swipe up after the long press, or swipe up and hold, for the capital.
- **Drags.** Drag left on a key to delete, right to bring it back. The left
  space moves the cursor, enter selects. Drags speed up with the finger,
  keep going at the screen edge, and go by words after a second flick.
  Hold delete to repeat.
- **Typing help.** Capitals at the start of a sentence, a period on a double
  space.
- **Symbols and emoji.** A symbols layer, and an emoji panel with recents and
  skin tones.
- **Make it yours.** Light, dark or custom colors, keyboard size, and keymaps:
  presets, a visual editor, or plain text for any long press and swipe.
- **Private.** No network access, nothing typed is stored, and password fields
  get no previews or recall. Works on the lock screen after a reboot.

## Install

Download the APK from the [latest release](https://github.com/kronberger-droid/hexboard/releases/latest),
open it, then enable Hexboard from the app or the system's keyboard settings.

## Building

With Nix, the flake's devShell provides everything:

```sh
nix develop
gradle testDebugUnitTest assembleDebug
```

Without Nix, install these and the same commands work:

- JDK 17 or newer
- Gradle 9 (there is no wrapper)
- The Android SDK with platform 36 and build-tools 36.1.0, with
  `ANDROID_HOME` pointing at it

The debug APK lands in `app/build/outputs/apk/debug/` and installs as
"Hexboard (debug)", beside a release.

## Inspirations

- [Typewise](https://www.typewise.app/), whose honeycomb layout this
  started from.
