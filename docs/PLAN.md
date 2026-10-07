# Plan

Each phase ends in something testable on the phone. Work one phase at a time;
a phase is finished when its done-when holds on the device, then mark it here.

| Phase | Status |
| --- | --- |
| 0 Toolchain | done |
| 1 Skeleton IME | done |
| 2 Hex grid | builds, tests pass; on-device check and layout review pending |
| 3 to 8 | open |

## Phase 0: Toolchain on NixOS

- Flake devShell with JDK, Gradle, and the SDK from android-nixpkgs;
  `ANDROID_HOME` set.
- aapt2 override pointing at the SDK binary.
- **Done when:** an empty app builds and installs with `adb install`.

## Phase 1: Skeleton IME

- `InputMethodService` subclass, manifest entry with `BIND_INPUT_METHOD`,
  `res/xml/method.xml`.
- The input view is one rectangle; tapping it commits `"a"`.
- **Done when:** it can be enabled in settings and types into any text field.

## Phase 2: Hex grid

- Axial coordinates to pixel centers, hexagons drawn with labels, keyboard
  height set in `onMeasure`.
- Nearest-center hit testing, unit-tested.
- A tap commits the key's primary character.
- The layout is a draft of Typewise's hex arrangement, reconstructed and then
  corrected by Martin on the device.
- **Done when:** the full layout renders and types lowercase letters.

## Phase 3: Swipes and shift

- Gesture classifier: tap if displacement is under a threshold in dp,
  otherwise one of six 60° sectors. Pure logic, unit-tested.
- Up gives uppercase; other directions map to per-key alternates from the
  layout data.
- Shift and caps lock, with labels showing the current case.
- **Done when:** capitals and punctuation type without extra keys.

## Phase 4: Multitouch

- Per-pointer gesture tracking (`ACTION_POINTER_DOWN`/`UP`, pointer IDs).
- **Done when:** fast rolling typing drops no characters.

## Phase 5: Backspace

- Tap deletes one grapheme cluster. Test with ZWJ emoji (👨‍👩‍👧) and flags.
- Scrub: dragging left selects by graphemes with a `setSelection` preview,
  dragging right shrinks the selection, release deletes. Optionally word
  steps past some distance.
- Recall buffer: a right swipe re-inserts the last deletion. The buffer is
  invalidated in `onUpdateSelection` when the cursor moves elsewhere.
- **Done when:** scrub and recall work in a normal app. Then try a browser
  and Termux; add a live-deletion fallback only if needed.

## Phase 6: Symbols and editor awareness

- Symbol layer toggle with its own layout data.
- Enter follows `EditorInfo.imeOptions` (send, search, go, newline).
- Number and phone fields optionally get a numeric layout.
- **Done when:** messaging, search bars, and number fields all behave
  sensibly.

## Phase 7: Emoji

- Build-time script (Nushell or a Gradle task) parses `emoji-test.txt`, keeps
  `fully-qualified` entries with their groups, writes an asset.
- Emoji panel swapped in for the key grid: scrollable grid, group tabs,
  recents row in `SharedPreferences`.
- **Done when:** emoji can be picked, recents persist, and backspace deletes
  them cleanly.

## Phase 8: Polish

- Haptics on key down, light and dark theme following the system, key press
  highlight.
- Optionally a small settings activity for key size, haptics, swipe
  threshold.
