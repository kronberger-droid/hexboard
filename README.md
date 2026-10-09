# hexboard

A hexagonal keyboard for Android in the spirit of Typewise, written in plain
Kotlin on the Android framework: no AndroidX, no Compose, no third-party
libraries.

- Honeycomb layout with two space keys in the middle and split keys for
  paired punctuation.
- Swipe up for capitals, down for lowercase; shift and caps lock.
  Sentences start with a capital, and a double space types a period.
- Long-press or swipe up-right on `a o u s` for `ä ö ü ß`.
- Drag left on almost any key to select backwards and delete, right to
  bring the deletion back. Drags accelerate like a trackpad pointer: slow
  for single characters, fast to cover distance.
- Drag the left space to move the cursor, or enter to select.
- Flick twice to go by words: a second flick within a moment moves,
  selects or deletes a whole word, and flicking then dragging goes word
  by word.
- Symbols layer, an emoji panel with recents, and enter that follows the
  text field's action.
- Light, dark or custom colors for the background, keys, labels, space
  keys and enter.
- Keymaps as text, with presets: rearrange both layers and give any key
  a long press and diagonal swipes (Settings, Edit keymap).

## Building

Needs Nix with flakes. The devShell provides the JDK, Gradle and the
Android SDK.

```sh
nix develop
gradle testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then enable "Hexboard" under the system's keyboard settings, or from the
Hexboard app, which also holds its settings.

## Releasing

Pushing a tag like `v1.2.3` makes CI build a signed, shrunk APK in the
same devShell and publish it as a GitHub release. The version comes from
the tag; its version code is `1·10000 + 2·100 + 3`.

The signing key lives outside the repository. Create it once, then store
it and its password as repository secrets as the script prints:

```sh
nix develop --command nu scripts/release-key.nu
```

Locally, `gradle assembleRelease` builds unsigned unless
`HEXBOARD_KEYSTORE` and `HEXBOARD_KEYSTORE_PASSWORD` point at the key.
A release cannot install over a debug build, which is signed with the
committed debug key; uninstall one before installing the other.

Progress and design notes live in [`docs/PLAN.md`](docs/PLAN.md).
