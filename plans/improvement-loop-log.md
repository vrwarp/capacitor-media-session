# Adversarial Improvement Loop — Ledger

This file is the shared memory for a 10-iteration adversarial
critique → ideation → improvement loop run against this library. Its goals are to
improve **reliability**, **usability**, and **feature richness** (e.g. custom actions).

Each iteration has three agents:
1. **Critic** (adversarial): finds the highest-impact real weaknesses in the current code.
2. **Ideator**: turns the critique into a concrete, scoped plan (1–3 changes) for this iteration.
3. **Implementer**: implements the plan, keeps the build green, adds tests/docs, commits.

## Ground rules / invariants (read before every iteration)

- The plugin has **two surfaces that must stay in sync**:
  - TypeScript API: `src/definitions.ts` (JSDoc drives the README via docgen) and `src/web.ts`.
  - Android native: `android/src/main/java/.../MediaSessionPlugin.java`,
    `WebViewProxyPlayer.java`, `MediaSessionService.java`.
  - Any new API method/option must be added to **both** TS and Android (web should degrade
    gracefully when the underlying Web Media Session API lacks the capability).
- The API mirrors the [Media Session Web API](https://w3c.github.io/mediasession/); keep it
  familiar and **backward compatible** unless there is a strong reason not to.
- `setActionHandler` is a `RETURN_CALLBACK` plugin method using `setKeepAlive(true)`.
- JavaScript is the source of truth; the native proxy player updates optimistically only for
  responsive system UI (play/pause, seek bar) and otherwise waits for JS confirmation.
- **Verification gate (must pass before committing each iteration):**
  - Web: `npm run build` (also regenerates README from JSDoc via docgen).
  - Android: `cd android && ./gradlew test` (unit tests: Robolectric + Mockito).
  - The Android SDK is at `/opt/android-sdk` (configured via `android/local.properties`).
- Do not break the example app (`example/`).
- Prefer adding/adjusting unit tests for every behavior change.

## Baseline (before iteration 1)

- API: `setMetadata`, `setPlaybackState`, `setActionHandler`, `setPositionState`.
- Android: Media3 `MediaSessionService` + `WebViewProxyPlayer` (SimpleBasePlayer proxy).
- Web/iOS: thin wrapper around `navigator.mediaSession`.
- Known gaps to seed ideation (not exhaustive — Critic should find more):
  - No custom/extra actions beyond the standard Media Session actions.
  - No way to read current state back, no events/listeners on the JS side.
  - No `seekOffset` plumbed through to handlers; `setActionHandler` returns void synchronously.
  - Error handling is best-effort; artwork blob: URLs unsupported on Android.
  - No iOS-native specifics; relies entirely on WebView Media Session API.

---

## Iteration log

<!-- Each iteration appends a section below. -->
