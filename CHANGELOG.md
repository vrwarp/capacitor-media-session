# Changelog

## 4.1.0

### Added

* **Custom actions (Android)** — register arbitrary non-standard action strings alongside the eight standard Media Session actions, with per-button `label`, `icon` (built-in Media3 icons), `iconUri` (a custom drawable URI) and `enabled` options.
* **Read-back getters** — `getMetadata()`, `getPlaybackState()` and `getPositionState()` return the last values set from the plugin's own cache.
* **Listeners** — `addListener('action', …)` fires for every action (standard and custom, including the `data` payload); `addListener('artworkload', …)` reports the artwork load outcome.
* **Async, size-aware artwork** — `http(s)://` and `data:` URIs, single best-fit image selection by size, downsampling with an 8&nbsp;MB cap, and bounded `http <-> https` cross-protocol redirect following.
* **Service lifecycle configuration** — the Android `foregroundService` key selects `'always'` (started at plugin load) vs. during-playback only.
* **Graceful Web/iOS degradation** — Web and iOS map onto `navigator.mediaSession`; custom actions are a silent no-op there.

These improvements were developed through an adversarial critique → ideation → improvement loop.
