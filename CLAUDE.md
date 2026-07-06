# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**BotMaker-shared** is the cross-platform native window plumbing shared by **both** the BotMaker SDK
(runtime, `../botmaker-sdk`) and the BotMaker Studio (editor, `../botmaker-studio`). It enumerates,
captures, focuses, moves, resizes and drives input to native OS windows. It depends only on **JNA** —
**no JavaFX, no OpenCV**. Full-desktop capture backends deliberately live in the *consumers*, not here.

## Contract stability

Because shared is consumed by two independent modules, **its public API is a shared contract** — the
`NativeController` interface and the `capture.*` value types (`GenericWindow`, `WindowInfo`) are compiled
against by both the SDK and the Studio. Treat signature changes as breaking for two downstream repos, not
one; land the shared change, release it, then bump both consumers (see the umbrella `../CLAUDE.md` and
`../release.sh`).

## Planning

For large changes, write the plan to a dedicated plan file before starting, so work can be resumed if a
session is interrupted. **Always update `ROADMAP.md` when you add a feature or refactor** — append a dated
entry under the newest-first history.

## Commands

```bash
mvn compile        # Build
mvn test           # Run tests (JUnit Jupiter; NativeControllerFactory.setForTesting injects a fake)
mvn install        # Install to the local Maven repo (also done by the umbrella reactor)
./dev-install.sh   # Install to ~/.m2 at 0.0.0-SNAPSHOT so consumers pick up local changes with no tag
```

This module is normally built from the umbrella root (`mvn install`), which builds it **first** so the SDK
and Studio resolve it from the reactor. There is no coordinate trick to test local changes: because the
groupId already matches JitPack, `./dev-install.sh` (a plain `mvn install`) lands it at the default
`0.0.0-SNAPSHOT` every consumer resolves. See `../CLAUDE.md` › Local dev.

## Architecture

`capture.NativeController` (interface) is the single cross-platform abstraction: window enumeration
(`getAllWindows` / `getChildWindows` / `getForegroundWindow`), per-window capture (`captureWindow`, returns
`null` when it can't produce a usable frame — e.g. native Wayland, invalid geometry — so callers apply their
own full-desktop fallback), window management (`focus`/`move`/`resize`), and input synthesis
(`keyDown`/`keyUp`/`typeText`/`mouseMove`/`mouseButton`/`scroll`, plus `postLeftClick*`). There is
**deliberately no `captureDesktop()`** here — full-desktop capture lives in each consumer.

`capture.NativeControllerFactory.get()` picks the implementation by OS (JNA `Platform`): `WindowsController`
or `LinuxController` (macOS throws `UnsupportedOperationException`). `setForTesting(...)` injects a fake for
tests. Key codes crossing the interface are **per-OS native codes** (X keysym on Linux, virtual-key code on
Windows); consumers resolve them from their own platform-neutral key enums.

Package map:
- `capture/` — the cross-platform surface: `NativeController`, `NativeControllerFactory`, `GenericWindow`.
- `capture/windows/` — JNA Windows backend: `User32`/`GDI32` bindings, `WindowsController`, `WindowFinder`,
  `WindowInfo`, `WindowCapture`, `Clicker`.
- `capture/linux/` — JNA Linux/X11 backend: `X11`/`XTest` bindings, `X11Utils`, `LinuxController`.

## groupId note

This module's Maven `groupId` is `com.github.LiQiyeDev` (not `com.botmaker.shared`) on purpose — it matches
the coordinate JitPack serves, so one dependency line in the SDK/Studio resolves both locally (reactor) and
from JitPack. See `pom.xml` and `../CLAUDE.md`.

## Code Style

Prefer functional OOP: minimize mutable state, keep the native side effects (JNA calls, window handles) at
the edges, pass dependencies in rather than reaching for singletons. The one intentional singleton is the
lazily-cached `NativeControllerFactory.instance` (overridable for tests).
