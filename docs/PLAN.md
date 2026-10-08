# Plan

Each phase ends in something testable on the phone. Work one phase at a time;
a phase is finished when its done-when holds on the device, then mark it here.

| Phase | Status |
| --- | --- |
| 0 Toolchain | done |
| 1 Skeleton IME | done |
| 2 Hex grid | done |
| 3 Swipes and shift | done |
| 4 Multitouch | done |
| 5 Backspace | done |
| 6 Symbols and editor awareness | done |
| 7 Emoji | done |
| 8 Polish | next |

## Workflow

One branch per phase or fix, cut from `main`. Martin merges with
`git merge --ff-only <branch>` after trying the APK on the phone and
pushes `main` himself. Every change ends with `gradle testDebugUnitTest
assembleDebug` inside `nix develop`, and on-device checks are named in
the hand-over because the JVM tests cannot see them.

## Open threads

Things noticed along the way that belong to no phase yet. Take them up
when Martin asks, or fold them into Phase 8 where they fit.

- **Drag tuning.** `SWIPE_THRESHOLD_DP` in `core/Gesture.kt`, and
  `DRAG_STEP_DP`, `DRAG_SLOW_DP_S`, `DRAG_FAST_DP_S`, `DRAG_GAIN_MAX`,
  `DRAG_SMOOTH_MS`, `DRAG_FLICK_MS`, `DRAG_EDGE_DP`, `DRAG_EDGE_RATE_MAX`
  in `core/Touches.kt`. Martin expects to tune these; a settings screen
  (Phase 8) could expose them.
- **Selection toolbar** after an enter drag uses a `SelectRangeGesture`
  (API 34+). Unverified in Chrome and Compose text fields; if it fails
  there, the fallback is an in-keyboard cut/copy/paste bar through
  `performContextMenuAction`.
- **Drag reach** is 2000 chars either way (`WINDOW` in the service).
- **Drag interplay untested:** a scrub started over an enter-drag selection
  ignores it; recall refuses while a selection exists.
- **Composing-text recall preview** not yet tried in a browser or Termux.
- **Symbols:** Typewise's second symbols page (`¥?±` on its function key)
  is not built; the mark on `=/+` in its screenshot was unreadable.
- **Emoji:** no skin tones (natural follow-up: long-press popup); the
  Recent tab only appears after the first pick, shifting the other tabs.

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
- The layout is Typewise's honeycomb in English (QWERTY y and z), read off a
  screenshot: five rows of 6 and 7 keys, split punctuation keys, bare edge
  keys.
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
- Scrub, from any key without swipe alternates (as in Typewise): dragging
  left selects by graphemes with a `setSelection` preview, release deletes.
  Keys with alternates keep their six directions instead.
- Recall buffer: dragging right on the same keys mirrors the scrub, bringing
  the last deletion back cluster by cluster as composing text; release
  keeps it, and what was not brought back stays recallable. The buffer is
  invalidated in `onUpdateSelection` when the cursor moves elsewhere.
- Sideways drags work like trackpad pointer acceleration (`DragGain`):
  the first step comes as the drag starts, a drag lifted within
  `DRAG_FLICK_MS` stops there, and otherwise it moves only while the
  finger does, one cluster per few dp when slow and many per dp when
  fast. Reach is 2000 chars either way of where the drag started
  (`WINDOW` in the service). Past what the finger can cover, holding it
  in the strip at the keyboard's edge keeps the drag going, faster the
  deeper in (`DragEdge`).
- The left space moves the cursor and enter extends a selection from it
  (`␣:move`, `⏎:select` in the layout). The right space scrubs like the
  letters, so a backspace drag that lands on it still deletes.
- `core/Touches` finishes every finger already down when a new one lands
  (Phase 4). A drag is long, so it is exempt from that.
- **Done when:** scrub and recall work in a normal app. Then try a browser
  and Termux; add a live-deletion fallback only if needed.

## Phase 6: Symbols and editor awareness

- Symbol layer toggle with its own layout data.
- Enter follows `EditorInfo.imeOptions` (send, search, go, newline).
- Number, phone and date fields open on the symbols layer, which carries
  the digits; no separate numeric layout.
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
