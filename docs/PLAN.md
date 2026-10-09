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
| 8 Polish | done |

## Workflow

One branch per phase or fix, cut from `main`. Martin merges with
`git merge --ff-only <branch>` after trying the APK on the phone and
pushes `main` himself. Every change ends with `gradle testDebugUnitTest
assembleDebug` inside `nix develop`, and on-device checks are named in
the hand-over because the JVM tests cannot see them.

## Open threads

Things noticed along the way that belong to no phase yet. Take them up
when Martin asks, or fold them into Phase 8 where they fit.

- **Drag tuning.** The settings screen exposes swipe distance, long-press
  delay, the swipe-up-and-hold delay, slow drag step, fast drag gain and
  edge top speed (`core/Settings.kt`). `PREVIEW_MS` in `core/Gesture.kt`
  sets when a held key shows its letter. The rest stay constants: `DRAG_SLOW_DP_S`,
  `DRAG_FAST_DP_S`, `DRAG_SMOOTH_MS`, `DRAG_FLICK_MS`, `DRAG_FLICK_DP_S`,
  `DRAG_EDGE_DP`, `DRAG_EDGE_RATE_START`, `DRAG_EDGE_RAMP_MS`, the
  delete repeat's `REPEAT_*`, and word mode's `WORD_CHAIN_MS` (how soon a
  second flick chains) and `WORD_STEP` (words per cluster step) in
  `core/Touches.kt`.
- **Logic outside `core`.** The scrub, cursor and recall drags keep their
  state and editor bookkeeping in `HexboardService`, and the visual keymap
  editor's touches and the emoji panel's hit testing live in their views;
  none of it has tests. Moving it into `core` is a larger refactor.
- **Release signing** runs in a GitHub environment named `release`. Restrict
  it to `v*` tags and move the signing secrets into it, so only release
  tags can use them. Bumping a Gradle dependency means regenerating
  `gradle/verification-metadata.xml` from an empty Gradle cache with
  `--write-verification-metadata sha256`.
- **Back gesture.** Edge keys ask to be excluded from the system back
  swipe, but Android grants at most 200 dp per edge and the keyboard is
  likely taller. Check on the phone that dragging left from `⌫` and enter
  never goes back; if one does, exclude only the keys that need it. Drags
  that reach the edge strip from inside are safe, since back only starts
  from a touch that lands at the edge.
- **Key-event drags** in editors that hide their text (Termux) are
  untried on the device.
- **Selection toolbar** after an enter drag uses a `SelectRangeGesture`
  (API 34+). Unverified in Chrome and Compose text fields; if it fails
  there, the fallback is an in-keyboard cut/copy/paste bar through
  `performContextMenuAction`.
- **Drag interplay:** a scrub started over a selection takes it as its
  first step; recall refuses while a selection exists.
- **Composing-text recall preview** not yet tried in a browser or Termux.
- **Symbols:** Typewise's second symbols page (`¥?±` on its function key)
  is not built; the mark on `=/+` in its screenshot was unreadable.

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
  the first step comes as the drag starts. A drag lifted within
  `DRAG_FLICK_MS` is a flick and keeps only that step; fast moves until
  then are held back, so a flick shows no selection.
  Otherwise the drag moves only while the finger does, one cluster per
  few dp when slow and many per dp when fast. Text is fetched 2000 chars
  at a time (`WINDOW` in the service), and more as a drag reaches the end
  of what it has. Past what the finger can cover, holding it
  in the strip at the keyboard's edge keeps the drag going like key
  repeat, faster the longer it stays (`DragEdge`).
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
- Built along the way: long press and up-right swipe for `ä ö ü ß`,
  auto-capitalization from the editor's caps mode, double space for `. `,
  emoji skin tones on long press, batched drag updates, key-event drags
  where the editor hides its text, drags that fetch more text past
  2000 characters, hold-to-repeat delete, keymaps as text with presets
  (`core/Keymap.kt`) and diagonal swipes on drag keys, and word-wise
  moving, selecting and deleting on a second flick, a capital long press
  by long press then swipe up or by swipe up and hold, and a held key's
  letter shown in the text that turns into its long press when due.
- **Done when:** all of the above hold on the phone, light and dark.
