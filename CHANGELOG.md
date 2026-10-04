# Changelog

## Unreleased

### Added

* **Opt-in audio focus and phone-call interruptions (Android).** `setAudioFocusPolicy({ mode: 'owned' })` makes the plugin request Android audio focus when playback starts and report calls, other apps taking over and unplugged headphones through a new `interruption` event (`phase`, `reason`, `shouldResume`). During a call the proxy player reports a transient-focus-loss suppression, so the media notification and foreground service survive a long call; the end is reported only once the audio mode is back to normal. Default `'none'` keeps the previous behaviour. No new permission; Media3 stays at 1.4.1.

## 4.2.0

Production-hardening release: test coverage, failure resilience, cross-platform parity and performance.

### Fixed

* **Malformed `artwork` entries no longer crash the app (Android).** A plain-JS caller passing e.g. `artwork: ["cover.png"]` (strings instead of `MediaImage` objects) used to raise a `ClassCastException` on the main looper — an app-killing crash from bad input. Non-object entries are now filtered defensively; well-formed entries in the same array still work.
* **A failed `bindService` no longer permanently disables the media session (Android).** When `bindService` returned `false` or threw (e.g. a `SecurityException` under OEM background restrictions), the internal binding flag stayed set forever and every later bind attempt was a silent no-op — no media notification for the rest of the app run. The connection is now released, the state reset, and the next `playing` state retries the bind. Service-connection bookkeeping was also fixed: a service disconnect keeps the (still live) binding registered instead of leaking it, and new `onBindingDied`/`onNullBinding` paths recover from dead/refused bindings.
* **Partial `setMetadata` updates preserve omitted fields on Web.** The merged cache is now what is handed to `new MediaMetadata(...)`, so `setMetadata({ artist })` keeps the earlier title and cover in the browser UI — matching the documented (and Android) semantics instead of wiping them.
* **Position-only `setPositionState` updates no longer throw on Web.** The merged position cache is passed to the browser, so omitting `duration` (documented as "preserves the previous value") no longer triggers the browser's `TypeError: duration must be present`.
* **A throwing action handler no longer swallows the `action` listener event on Web** (Android already emitted it unconditionally).
* **`data:` artwork URIs now honor the documented 8 MB cap (Android).** Only the HTTP path enforced `MAX_ARTWORK_BYTES`; a huge embedded base64/percent-encoded payload could allocate unbounded decode buffers. A string-length pre-guard rejects oversized URIs before decoding and the exact byte cap is enforced after.
* **Non-finite times can no longer corrupt the Media3 timeline (Android).** A `duration` of `Infinity` (what JS reports for live streams) saturated `Math.round` and overflowed the microsecond conversion into a negative duration; `NaN`/`Infinity` playback rates could reach position extrapolation. Non-representable values now render as an indeterminate timeline / 1x speed.
* **Teardown races are dropped safely (Android).** A `setActionHandler` registration landing after `handleOnDestroy` releases its kept-alive call instead of mutating torn-down state; a late controller tap after destroy is discarded; the service's plugin back-reference is detached on teardown so nothing routes into a destroyed plugin.

### Changed

* **Android `setPlaybackState` now rejects values outside `'none' | 'paused' | 'playing'`** for parity with the browser's `TypeError`, instead of silently caching the garbage value (which also tore the service down and echoed back through `getPlaybackState`).

### Performance

* **`MediaMetadata` is cached in the proxy player (Android).** `getState()` runs on every invalidation and position tick and used to rebuild the metadata — re-cloning the artwork bytes (~50–150 KB) each time. It is now rebuilt only when title/artist/album/artwork actually change; steady-state per-second position updates reuse the built instance.

### Testing / tooling

* **The TypeScript layer is now unit-tested** (previously zero direct coverage — the example suite mocks the plugin away): a root vitest + jsdom harness with a spec-faithful `navigator.mediaSession`/`MediaMetadata` fake covers 100% of `src/web.ts` and the `src/index.ts` proxy, including the `removeHandler` translation, event wrapping and every unavailable/error path. Run with `npm test` (also `test:watch`, `test:coverage`).
* **Android suite grew from 160 to 195 tests.** The artwork HTTP fetch loop — previously untested at the socket level by design — is now driven over real loopback sockets by a minimal hand-rolled server (`java.net` only, no new dependency): terminal 200 + downsampling, relative/absolute redirect chains, the redirect hop-cap boundary (5 follows succeed, 6 abort), the redirect-loop visited-set guard, non-200 responses, the `Content-Length` fast-fail, the streaming byte-cap abort, and connection failures. New regression tests cover every fix above.
* **CI runs the web unit tests** (`verify:web` = build + test), and publishing (`prepublishOnly`) is gated on them too.

### Also in this release (previously unreleased)

* **Fixed: a failed media session no longer kills the app (Android).** `MediaSessionService.onCreate` rethrew an `IllegalStateException` from `MediaSession.Builder.build()` / `addSession()` — most plausibly the "Session ID must be unique" collision against a not-yet-released prior session. An exception out of `Service.onCreate` terminates the whole app process, and the plugin binds the service at load time whenever `foregroundService` is `'always'`, so this could kill the host app on launch with no crash dialog. Session creation is now retried once with a fresh id; if it still fails the service starts without a session instead of throwing. A degraded start also releases its unowned proxy player on destroy rather than leaking it.
* **Added: `addListener('sessionunavailable', …)` (Android)** — reports that the media session could not be created, so the run has no media notification and no lock-screen or hardware-media-button controls. Carries a diagnostic `reason`, and is retained until consumed since the service binds before the web app can register a listener.

## 4.1.0

### Added

* **Custom actions (Android)** — register arbitrary non-standard action strings alongside the eight standard Media Session actions, with per-button `label`, `icon` (built-in Media3 icons), `iconUri` (a custom drawable URI) and `enabled` options.
* **Read-back getters** — `getMetadata()`, `getPlaybackState()` and `getPositionState()` return the last values set from the plugin's own cache.
* **Listeners** — `addListener('action', …)` fires for every action (standard and custom, including the `data` payload); `addListener('artworkload', …)` reports the artwork load outcome.
* **Async, size-aware artwork** — `http(s)://` and `data:` URIs, single best-fit image selection by size, downsampling with an 8&nbsp;MB cap, and bounded `http <-> https` cross-protocol redirect following.
* **Service lifecycle configuration** — the Android `foregroundService` key selects `'always'` (started at plugin load) vs. during-playback only.
* **Graceful Web/iOS degradation** — Web and iOS map onto `navigator.mediaSession`; custom actions are a silent no-op there.

These improvements were developed through an adversarial critique → ideation → improvement loop.
