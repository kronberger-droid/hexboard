<h1 align="center"><img src="docs/wordmark.svg" width="380" alt="Hexboard"></h1>

A hexagonal keyboard for Android. Swipe a key for capitals and extras,
drag sideways to delete, select or move the cursor, and make the layout
your own.

<p align="center">
  <img src="docs/screenshots/dark.png" width="45%" alt="Hexboard in the dark theme">
  <img src="docs/screenshots/light.png" width="45%" alt="Hexboard in the light theme">
</p>

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

## Inspirations

- [Typewise](https://www.typewise.app/), whose honeycomb layout this
  started from.
