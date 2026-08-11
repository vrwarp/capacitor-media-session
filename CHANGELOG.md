# Changelog

## Unreleased

### Fixed

* **A failed media session no longer kills the app (Android).** `MediaSessionService.onCreate` rethrew an `IllegalStateException` from `MediaSession.Builder.build()` / `addSession()` — most plausibly the "Session ID must be unique" collision against a not-yet-released prior session. An exception out of `Service.onCreate` terminates the whole app process, and the plugin binds the service at load time whenever `foregroundService` is `'always'`, so this could kill the host app on launch with no crash dialog. Session creation is now retried once with a fresh id; if it still fails the service starts without a session instead of throwing. A degraded start also releases its unowned proxy player on destroy rather than leaking it.

### Added

* **`addListener('sessionunavailable', …)` (Android)** — reports that the media session could not be created, so the run has no media notification and no lock-screen or hardware-media-button controls. Carries a diagnostic `reason`, and is retained until consumed since the service binds before the web app can register a listener.

## 4.1.0

### Added

* **Custom actions (Android)** — register arbitrary non-standard action strings alongside the eight standard Media Session actions, with per-button `label`, `icon` (built-in Media3 icons), `iconUri` (a custom drawable URI) and `enabled` options.
* **Read-back getters** — `getMetadata()`, `getPlaybackState()` and `getPositionState()` return the last values set from the plugin's own cache.
* **Listeners** — `addListener('action', …)` fires for every action (standard and custom, including the `data` payload); `addListener('artworkload', …)` reports the artwork load outcome.
* **Async, size-aware artwork** — `http(s)://` and `data:` URIs, single best-fit image selection by size, downsampling with an 8&nbsp;MB cap, and bounded `http <-> https` cross-protocol redirect following.
* **Service lifecycle configuration** — the Android `foregroundService` key selects `'always'` (started at plugin load) vs. during-playback only.
* **Graceful Web/iOS degradation** — Web and iOS map onto `navigator.mediaSession`; custom actions are a silent no-op there.

These improvements were developed through an adversarial critique → ideation → improvement loop.
