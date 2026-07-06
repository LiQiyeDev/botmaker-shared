# ROADMAP

A running history of features and refactors for `botmaker-shared`, for future Claude Code sessions.
**Append here whenever you add a feature or refactor** (required — see `CLAUDE.md` › Planning).

Format: newest first. Each dated entry has a **Done** list and, when relevant, **Deferred / next**
(intentionally left for later, with enough context to pick up cold).

---

## 2026-07-06 — Cursor-preserving background input (pluggable Linux backends)

**Done**
- **Replaced the single XTest click path with a pluggable `LinuxInputBackend`** (`capture/linux/input/`),
  selected by the `botmaker.linux.input` system property / `BOTMAKER_LINUX_INPUT` env var
  (`auto` → xsendevent | `xsendevent` | `uinput` | `xtest`). `auto` never picks a cursor-moving backend.
  `LinuxController` now delegates all input synthesis (`postLeftClick*`, `mouseMove`, `mouseButton`,
  `keyDown/keyUp`, `typeText`, `scroll`) to the chosen backend and destroys it in `close()`.
- **`XSendEventBackend` (new default) — cursor-preserving background clicking.** Delivers synthetic
  `ButtonPress`/`ButtonRelease`/`MotionNotify`/`Key*` straight to the target window via new `X11.XSendEvent`
  + an `XButtonEvent`/`XEvent` JNA struct (padded past `sizeof(XEvent)`); drills to the leaf child under the
  point with `XTranslateCoordinates`. The real cursor never moves and the target need not be focused/on top.
  Reaches X11/XWayland windows — exactly the set this module enumerates/captures. **Verified on KDE Wayland:**
  clicks land on a Swing target while the real cursor stays put.
- **`UinputBackend` — reliable-everywhere opt-in, pure Java** (JNA to `/dev/uinput`, no `ydotool`/`dotool`).
  Absolute virtual pointer + keyboard; the key fix for KWin/GNOME was `UI_SET_PROPBIT INPUT_PROP_POINTER`
  (without it the ABS axes are ignored) plus a tight key-capability set. **Verified:** clicks land precisely
  (cursor warps to the exact target) incl. apps XSendEvent can't reach. Moves the shared cursor (documented);
  falls back to xsendevent if `/dev/uinput` can't be opened. Includes an X-keysym → evdev `KEY_*` table.
- **`XTestBackend`** — the legacy warp-and-click, extracted verbatim, now opt-in only.
- **Additive `NativeController.supportsBackgroundInput()`** (default `false`) reports whether the active
  backend leaves the cursor untouched — non-breaking for the SDK/Studio.

**Deferred / next**
- **Reliable *and* cursor-safe on native Wayland** — impossible in-process (one seat, no per-window
  injection). Needs either the RemoteDesktop portal + libei (still moves the cursor, one-time permission
  dialog) or running the target inside a bot-owned nested/headless compositor (Xephyr/gamescope) — a
  consumer-level (SDK/Studio) architecture change, not a shared change.
- **uinput multi-monitor** — the absolute device maps across a single output; clicks on secondary monitors
  may need per-output coordinate handling.

## 2026-07-06 — Extracted into a standalone shared module

**Done**
- **Extracted the cross-platform native window plumbing** (`capture.*` — `NativeController`,
  `NativeControllerFactory`, `GenericWindow`, and the `windows/` + `linux/` JNA backends) out of the SDK
  and Studio into this dedicated module so both consume one copy instead of duplicating it.
- **Coordinate:** published as `com.github.LiQiyeDev:botmaker-shared` (JitPack); the umbrella reactor
  builds it first. The SDK and Studio depend on it via a `${botmaker.shared.version}` property
  (`0.0.0-SNAPSHOT` in the reactor, a real tag once released).
- **Depends only on JNA** — no JavaFX, no OpenCV. Full-desktop capture backends stay in the consumers.

**Deferred / next**
- **macOS backend** — `NativeControllerFactory.get()` currently throws `UnsupportedOperationException`
  on macOS; a Cocoa/CoreGraphics `NativeController` would light up the Mac path for both consumers.
- **Wayland-native per-window capture** — `captureWindow` returns `null` under native Wayland today;
  a portal/PipeWire backend here would remove the consumers' full-desktop fallback for that case.
