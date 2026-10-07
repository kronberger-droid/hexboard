# hexboard

A hex grid keyboard (Android IME) in the spirit of Typewise. Built in phases,
each ending in something testable on the phone. **Before starting or closing a
phase, read [`docs/PLAN.md`](docs/PLAN.md)**: it holds every phase's scope,
its done-when, and which phases are finished.

## Ground rules

- Plain Kotlin on the Android framework. Dependencies are limited to the
  Android platform itself; the only external artifact is JUnit, test scope.
  No AndroidX, no Compose.
- One Gradle module (`:app`), one `InputMethodService`, custom `View`s drawn on
  a `Canvas`.
- `minSdk` 26.
- **Pure-logic core.** Everything under `dev.kronberger.hexboard.core` imports
  only Kotlin stdlib: hex geometry, hit testing, gesture classification, scrub
  math, layout data. Each piece gets JUnit tests in `app/src/test/kotlin`.
  Android code outside `core` stays a thin adapter that feeds touch events in
  and draws what `core` returns.
- Graphemes follow the same split. The adapter finds cluster boundaries with
  `android.icu.text.BreakIterator` and hands `core` a list of boundary
  indices; scrub math works on those indices, and its tests use hand-written
  boundary lists.
- Layouts are data (a Kotlin object or JSON asset). Drawing code reads the
  layout; it never names a key.

## Commands

Everything runs inside the flake's devShell (`nix develop`), which provides
JDK 21, Gradle 9, the Android SDK, and `adb`. Gradle comes from nixpkgs; there
is no wrapper.

- `gradle testDebugUnitTest` runs the `core` tests on the JVM, no device.
- `gradle assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` puts it on the
  phone (connected over wireless debugging; no emulator).
- `adb shell ime enable dev.kronberger.hexboard/.HexboardService` and
  `adb shell ime set dev.kronberger.hexboard/.HexboardService` switch to it
  without the settings UI.

Use the devShell's `adb`. The system also ships one, and two `adb` versions
kill each other's server.

## NixOS gotchas

- **SDK versions are pinned in two places.** `flake.nix` (`buildTools` and the
  SDK package list) and `app/build.gradle.kts` (`compileSdk`,
  `buildToolsVersion`) must agree. The SDK sits read-only in the store, so a
  version AGP wants but the flake lacks fails with an SDK manager error that
  hides the cause. Bump both together.
- **aapt2** from Maven is dynamically linked. The devShell passes
  `android.aapt2FromMavenOverride` through `GRADLE_OPTS` to the SDK's own
  binary. If a build reports it cannot execute aapt2, the shell was not
  entered.
- **AGP 9 compiles Kotlin itself.** Applying `org.jetbrains.kotlin.android`
  on top makes the build fail.
- The flake only sees git-tracked files; `git add` new ones before
  `nix develop`.

## IME facts worth keeping

- The service in `AndroidManifest.xml` needs `android:exported="true"` and the
  `BIND_INPUT_METHOD` permission; the manifest merger rejects it otherwise.
- `onEvaluateFullscreenMode()` returns false, so landscape keeps the keyboard
  instead of a fullscreen extract editor.
