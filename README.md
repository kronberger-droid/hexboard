# hexboard

A hexagonal keyboard for Android in the spirit of Typewise, written in plain
Kotlin on the Android framework: no AndroidX, no Compose, no third-party
libraries.

- Honeycomb layout with two space keys in the middle and split keys for
  paired punctuation.
- Swipe up for capitals, down for lowercase; shift and caps lock.
- Long-press `a o u s` for `ä ö ü ß`.
- Drag left on almost any key to select backwards and delete, right to
  bring the deletion back. Drags accelerate like a trackpad pointer: slow
  for single characters, fast to cover distance.
- Drag the left space to move the cursor, or enter to select.
- Symbols layer, an emoji panel with recents, and enter that follows the
  text field's action.

## Building

Needs Nix with flakes. The devShell provides the JDK, Gradle and the
Android SDK.

```sh
nix develop
gradle testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then enable "Hexboard" under the system's keyboard settings.

Progress and design notes live in [`docs/PLAN.md`](docs/PLAN.md).
