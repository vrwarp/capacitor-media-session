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

### Iteration 1 — handler removal, seekOffset delivery, web error handling

**Shipped**

- **R1 — `setActionHandler(action, null)` now removes the handler and re-registration
  does not leak.** Android `setActionHandler` was rewritten to (a) reject when `action`
  is missing, (b) always release the previously stored kept-alive `PluginCall` before
  replacing or removing it (fixing a `RETURN_CALLBACK` leak), and (c) honor a
  `removeHandler` flag: when set, it drops the handler, refreshes the proxy player's
  available commands, and `resolve()`s instead of keeping the call alive. The TS layer
  translates `handler === null` into `removeHandler: true` so the native bridge (which
  drops a `null` callback argument) can see the removal. Implemented as a thin `Proxy`
  wrapper in `src/index.ts` around the registered plugin — the public signature
  `setActionHandler(options, handler)` is unchanged and all other methods delegate
  untouched. Web/iOS keep getting `null`-removal for free from `navigator.mediaSession`.
- **U1 — `seekOffset` is now delivered to seekforward/seekbackward on Android.** The
  proxy player reports symmetric 10 s seek increments to Media3
  (`SEEK_FORWARD_INCREMENT_MS`/`SEEK_BACK_INCREMENT_MS` = 10000) and emits
  `seekOffset` (in seconds) to the JS handler for `COMMAND_SEEK_FORWARD`/
  `COMMAND_SEEK_BACK`; all other actions pass `seekOffset = null`. `ActionCallback.onAction`
  gained a third `seekOffset` parameter; `MediaSessionPlugin.onPlayerAction` puts
  `seekTime` and/or `seekOffset` into the resolved `JSObject` when non-null.
- **R2 — Web `setActionHandler` no longer swallows errors.** The
  `navigator.mediaSession.setActionHandler(...)` call is wrapped in try/catch and rethrows
  `this.unavailable("Action \"<action>\" is not supported in this browser.")`, consistent
  with the file's existing error model (some browsers throw `NotSupportedError` for
  unsupported actions).

**Files changed**

- `src/definitions.ts` — JSDoc on `setActionHandler` now documents that passing `null`
  removes the handler. (No `removeHandler` field on the public interface — see deviation.)
- `src/index.ts` — `Proxy` wrapper injecting `removeHandler: true` on `null` handlers.
- `src/web.ts` — try/catch around `setActionHandler`.
- `android/.../MediaSessionPlugin.java` — rewritten `setActionHandler`; `onPlayerAction`
  now carries `seekOffset`.
- `android/.../WebViewProxyPlayer.java` — `ActionCallback.onAction(action, seekTime,
  seekOffset)`; seek-increment constants; `setSeekForwardIncrementMs/setSeekBackIncrementMs`
  in `getState()`; `handleSeek` emits the increment as `seekOffset`.
- `android/.../MediaSessionPluginTest.java`, `WebViewProxyPlayerTest.java` — updated
  helper + callback, new tests (below).
- `README.md` — regenerated by docgen (the new `null`-removal sentence).

**Test cases added/updated**

- `MediaSessionPluginTest` (now 23 tests): re-registration releases the previous call and
  keeps the new one live; `removeHandler=true` removes the handler, drops
  `COMMAND_PLAY_PAUSE`, releases the stored call and resolves (not kept alive); removing a
  never-registered action does not throw and resolves; missing `action` rejects; seekforward
  resolves a handler with `seekOffset` in seconds and no `seekTime`. `mockActionHandlerCall`
  now stubs `isKeptAlive()`→true/`isReleased()`→false/`getBoolean("removeHandler", …)`→false;
  added `mockRemoveHandlerCall`.
- `WebViewProxyPlayerTest` (now 28 tests): callback captures `seekOffset`;
  seekforward/seekbackward assert the increment in **seconds** with null seekTime; seekto
  asserts a null offset.

**Verification — both gates green**

- Web: `npm run build` → docgen + tsc + rollup all succeeded (exit 0).
- Android: `cd android && ./gradlew test` → `BUILD SUCCESSFUL`; 64 tests, 0 failures/errors
  (ArtworkScalingTest 5, MediaSessionPluginTest 23, MediaSessionServiceTest 8,
  WebViewProxyPlayerTest 28).

**Deviation from plan**

- The plan said to add `removeHandler?: boolean` to `ActionHandlerOptions` marked
  `/** @internal */` so docgen omits it. This docgen version (`@capacitor/docgen`) only
  honors `@hidden`/`@internal` on interface **methods**, not on interface **properties** —
  the field leaked into the README. To keep the README clean while preserving type safety,
  `removeHandler` is **not** placed on the public `ActionHandlerOptions`; instead the
  `index.ts` wrapper types the patched options locally as
  `ActionHandlerOptions & { removeHandler?: boolean }`. Public surface and behavior are
  exactly as intended; only the location of the internal field differs.

**Deferred (seeds for the next critic)**

- **F1** — custom/extra actions beyond the standard Media Session actions.
- **R3** — artwork: `blob:` URLs still unsupported on Android; no artwork failure surfaced
  to JS.
- **F2** — docgen cannot hide internal interface fields (`@internal`/`@hidden` only work on
  methods); blob artwork handling; position/duration defaulting and reading state back
  (no events/listeners on the JS side; `setActionHandler` still returns void-on-register).

### Iteration 2 — Custom actions (first slice)

**Shipped** (addresses deferred **F1**)

Full round trip for **custom actions**: JS registers a non-standard action string with a
`label` (+ optional `icon`) → Android publishes a Media3 `CommandButton` in the session's
custom layout → tapping it routes through `MediaSession.Callback.onCustomCommand` back to
`MediaSessionPlugin.actionCallback(customId, data)` → the kept-alive JS handler fires. Web/iOS
degrade to a silent no-op. Re-registering replaces the button (toggle); `setActionHandler(action,
null)` removes it (reuses the iteration-1 removal lifecycle). Standard-action callers are
unaffected; default session + player commands are preserved on connect; all session mutation runs
on the main/app looper.

- **TS API (`src/definitions.ts`)** — widened `ActionHandlerOptions.action` and
  `ActionDetails.action` to `MediaSessionAction | string` (backward-compatible superset). Added
  optional `label?: string` and `icon?: MediaSessionActionIcon` to `ActionHandlerOptions`, plus a
  new `MediaSessionActionIcon` string-literal type (24 values, each mapped to a
  `CommandButton.ICON_*` constant; unknown/missing → `ICON_UNDEFINED`). Extended the
  `setActionHandler` JSDoc to document custom actions (Android-only `label`/`icon`, `label`
  required to render, no-op on Web/iOS). These are public fields and intentionally surface in the
  README (no docgen-hiding attempted — unlike the iteration-1 `removeHandler` deviation).
- **TS Web (`src/web.ts`)** — added a `STANDARD_ACTIONS` set of the eight standard strings;
  `setActionHandler` resolves as a silent no-op for any non-standard action, otherwise keeps the
  iteration-1 try/catch path unchanged.
- **TS bridge (`src/index.ts`)** — no logic change; the `Proxy` already spreads all options, so
  `label`/`icon` forward automatically and typecheck against the widened interface.
- **Android shared (`CustomActions.java`, new)** — single source of truth: the eight-string
  `STANDARD_ACTIONS` set + `isStandard`/`isCustom`, the icon-literal → `CommandButton.ICON_*`
  mapping (`iconConstant`), and the insertion-ordered `CustomActionSpec` (id, label, icon
  constant).
- **`MediaSessionService.java`** — attached a `MediaSession.Callback` (`CustomActionsCallback`)
  via `MediaSession.Builder.setCallback(...)`; added `setPlugin(...)` back-reference. `onConnect`
  seeds from `ConnectionResult.DEFAULT_SESSION_COMMANDS` (preserving defaults), adds one
  `SessionCommand(id, Bundle.EMPTY)` per registered custom action, and sets the custom layout via
  `AcceptedResultBuilder`. `onCustomCommand` routes `sessionCommand.customAction` →
  `plugin.actionCallback(...)` and returns `RESULT_SUCCESS`. Added `updateCustomActions(specs)`
  (posts to the main looper, rebuilds the ordered `List<CommandButton>`, calls
  `mediaSession.setCustomLayout(...)`, and re-grants commands to already-connected controllers via
  `getConnectedControllers()` + `setAvailableCommands(...)`). Added test accessors `getMediaSession()`
  and `getSessionCallback()`.
- **`MediaSessionPlugin.java`** — `setActionHandler` now maintains a parallel insertion-ordered
  `LinkedHashMap<String, CustomActionSpec>` for custom ids (capturing `label`/`icon`), with
  register/re-register (remove-then-put for toggle ordering)/remove upkeep. The proxy player's
  `supportedActions` is filtered to standard actions only, so custom actions never reach the
  `Player.Commands` switch. Added `updateCustomLayout()` (guarded post to `service.updateCustomActions`),
  called from `setActionHandler` (register + remove) and from `onServiceConnected` (replays actions
  registered before the service bound); `onServiceConnected` also calls `service.setPlugin(this)`.

**Files changed**

- `src/definitions.ts`, `src/web.ts` (TS surface); `src/index.ts` unchanged (verified).
- `android/.../CustomActions.java` (new), `MediaSessionService.java`, `MediaSessionPlugin.java`.
- `android/.../MediaSessionPluginTest.java`, `MediaSessionServiceTest.java` (tests).
- `README.md` — regenerated by docgen (custom-action JSDoc + `MediaSessionActionIcon` type table).

**Test cases added** (8 new)

- `MediaSessionPluginTest` (23 → 30): registering `{action:'like', label:'Like', icon:'heart'}`
  publishes exactly one `CommandButton` (`customAction=="like"`, `displayName=="Like"`,
  `icon==ICON_HEART_UNFILLED`); a custom action enables no `Player.Command`; `onCustomCommand("like")`
  resolves the kept-alive handler with `action=="like"` and the future yields `RESULT_SUCCESS`;
  `onCustomCommand("ghost")` resolves no handler and does not throw; re-registering with
  `heart-filled`+new label releases the previous call and toggles the single button; `removeHandler`
  removes the button and releases the call; `onConnect` returns an accepted `ConnectionResult`
  whose available session commands include `SessionCommand("like")` **and** all defaults, with the
  button in the custom layout. New helper `mockCustomActionHandlerCall(action, label, icon)`.
- `MediaSessionServiceTest` (8 → 9): a fresh service has an empty custom layout and an attached
  session callback.

**Verification — both gates green**

- Web: `npm run build` → clean + docgen + tsc + rollup all succeeded (exit 0); README regenerated
  and committed.
- Android: `cd android && ./gradlew test` → `BUILD SUCCESSFUL`; **72 tests, 0 failures / 0 errors**
  (ArtworkScalingTest 5, MediaSessionPluginTest 30, MediaSessionServiceTest 9,
  WebViewProxyPlayerTest 28).

**Deviation from plan**

- The plan's button-build snippet used `setIconResId(...)`; instead the implementation passes the
  icon as a built-in icon **constant** via `new CommandButton.Builder(iconConstant)` (Media3 1.4.1
  resolves the bundled drawable internally), falling back to the no-arg builder for `ICON_UNDEFINED`.
  This is more faithful to "icon-constant" mapping and lets tests assert `button.icon` directly. No
  `getIconResIdForIconConstant` round-trip needed. All other details implemented as specified; all
  named Media3 1.4.1 APIs (`CommandButton.ICON_*`, `MediaSession.Callback`,
  `ConnectionResult.AcceptedResultBuilder`, `SessionCommands.buildUpon`, `setAvailableCommands`,
  `getConnectedControllers`) verified present in the 1.4.1 jar.

**Deferred (seeds for the next critic)**

- **F3** — arbitrary bitmap icons for custom actions (currently limited to the curated built-in
  `CommandButton.ICON_*` set; no `iconUri`/custom drawable threading).
- **F4** — events / state read-back on the JS side (still no listeners; `setActionHandler` returns
  void-on-register; custom-action button enabled/disabled state is not reported back to JS).
- **R3** (carried) — artwork `blob:` URLs still unsupported on Android; no artwork failure surfaced
  to JS; R-1 artwork threading for custom buttons not attempted.
- **F5** — `onCustomCommand` arg payload: the `args` Bundle is currently dropped (handler receives
  only `{action}`); no per-tap data is plumbed through to JS.

### Iteration 3 — artwork reliability + position defaulting

**Shipped** (addresses carried **R3** artwork threading/failure handling + position defaulting)

Three reliability/usability fixes, all contained to `MediaSessionPlugin.java` + tests + JSDoc (no
`WebViewProxyPlayer`/`MediaSessionService`/`CustomActions`/`web.ts`/`index.ts` logic touched, no
public TS or native method signature changes):

- **R-A — artwork fetch moved OFF the Capacitor bridge thread.** Every `@PluginMethod` runs on one
  shared background HandlerThread, so the old synchronous `urlToArtworkData` (`HttpURLConnection`
  5 s connect + 5 s read) in `setMetadata` could stall **all** other plugin calls for up to 10 s per
  bad artwork URL. `setMetadata` now (1) applies text fields synchronously with current-field
  defaults, (2) returns immediately if the `artwork` key is absent, otherwise (3) posts a runnable to
  the main looper that bumps a generation token, selects one src, pushes an immediate text-only state
  update, and submits the blocking fetch to a dedicated single-thread daemon `ExecutorService`
  (`"media-session-artwork"`). `call.resolve()` happens on the bridge thread right after posting — the
  promise no longer waits on the network. The fetch result is delivered back on the main looper and
  discarded if a newer request superseded it. `artworkData` is now `volatile` (written on main,
  read by the bridge-thread `updateServiceState`); `artworkGeneration` is main-thread-confined
  (single writer). Added a nested `interface ArtworkFetcher` + `private ArtworkFetcher artworkFetcher
  = this::urlToArtworkData` with a package-private `setArtworkFetcher(...)` test seam (no network in
  tests), and `artworkExecutor.shutdownNow()` in `handleOnDestroy()` before `super`.
- **R-B — size-aware single-image selection + stale-clearing.** Added pure static
  `parseMaxEdge(String sizes)` (space-separated `WxH`, case-insensitive `x`; entry max-edge =
  largest `max(W,H)`; `"any"` → `ANY_EDGE` sentinel; missing/empty/unparseable → `0`) and
  `selectArtworkSrc(List<JSONObject>, targetEdge)` (skip null/empty `src`; prefer the **smallest**
  entry whose max-edge ≥ target; else the **largest** available; all-unknown → last usable;
  last-wins on ties; `"any"` resolves to `targetEdge` so it ties an ideal raster). `setMetadata`
  now fetches **exactly one** image (selected with `MAX_ARTWORK_DIMENSION` = 512) instead of looping
  every entry. **Stale-clearing rule:** any supplied `artwork` array makes `artworkData` reflect
  THAT array's outcome — array present + usable src → set to the fetched bytes (or `null` if the
  fetch throws/returns null, the explicit fix: assign unconditionally); array present but no usable
  src (empty array / all entries unusable) → clear without fetching; only an **absent** `artwork`
  key preserves the previous cover. (The existing `blob:`-unsupported branch returns `null`, which
  now correctly clears rather than silently keeping a stale cover.)
- **U-A — `setPositionState` field defaulting.** `duration`/`position`/`playbackRate` now default to
  the previously stored values (`call.getDouble("duration", this.duration)`, etc.) instead of
  `0/0/1`, mirroring `setMetadata`'s text defaulting. Omitting a field preserves it; pass `0/0/1`
  explicitly to reset.

**Files changed**

- `android/.../MediaSessionPlugin.java` — async artwork pipeline, `ArtworkFetcher` seam + executor +
  generation token, `parseMaxEdge`/`selectArtworkSrc`/`ANY_EDGE`, rewritten `setMetadata`, position
  defaulting, executor shutdown in `handleOnDestroy`, `awaitArtworkIdle(ms)` test hook.
- `android/.../ArtworkSelectionTest.java` (new) — 13 pure-logic tests for the selector helpers.
- `android/.../MediaSessionPluginTest.java` — 8 new tests + a deterministic `drainArtwork()` helper;
  the three pre-existing artwork tests updated to drain the executor (they previously assumed
  synchronous application).
- `src/definitions.ts` — `setMetadata`/`setPositionState` JSDoc documenting the Android async/single-
  selection/clear-vs-preserve and position-defaulting semantics.
- `README.md` — regenerated by docgen.

**Test cases added/updated**

- `ArtworkSelectionTest` (13): `parseMaxEdge` ("96x96 128x128"→128, "512x512"→512, "1024x768"→1024,
  case-insensitive "128X128"→128, ""/null/"garbage"/"128"/"x"→0, "any"/"ANY"→`ANY_EDGE`);
  `selectArtworkSrc` picks smallest ≥512 (128/512/1024→512), largest when none reach 512
  (96/128/256→256), last usable when sizes unknown, skips null/empty src, empty/none→null,
  "any" ties an exact 512 (last-wins, both orders), ties→last entry.
- `MediaSessionPluginTest` (30 → 38): resolve-before-fetch (latch-blocked fetcher; `resolve()`
  observed before bytes land); async delivery (fixed bytes equal player artworkData after drain);
  new failing-artwork array CLEARS a prior valid base64 cover while the title updates; absent
  `artwork` key preserves the cover; empty array clears + fetcher never runs; generation discards the
  stale older result (A blocked, B newer → final is B's bytes); selector integration (three base64
  PNGs of different sizes → the decoded 512 one is chosen); `setPositionState` preserves omitted
  duration/playbackRate while updating position. The three legacy base64-artwork tests now call
  `drainArtwork()` (idle main → `awaitArtworkIdle` → idle main) instead of a single `idleMainLooper()`.

**Verification — both gates green**

- Web: `npm run build` → clean + docgen + tsc + rollup all succeeded (exit 0); README regenerated and
  committed.
- Android: `cd android && ./gradlew test` → `BUILD SUCCESSFUL`; **93 tests, 0 failures / 0 errors**
  (ArtworkScalingTest 5, ArtworkSelectionTest 13, MediaSessionPluginTest 38, MediaSessionServiceTest 9,
  WebViewProxyPlayerTest 28).

**Deviation from plan**

- The plan specified `parseMaxEdge(String sizes)` and that it "returns `targetEdge`" for `"any"`. A
  single-arg method has no `targetEdge`, so `"any"` returns an `ANY_EDGE` (`Integer.MAX_VALUE`)
  sentinel that `selectArtworkSrc` substitutes with `targetEdge`. Behaviour ("any" ties an ideal
  raster) is exactly as specified; the tested single-arg signature is preserved.
- `ArtworkSelectionTest` runs under Robolectric (not a bare JUnit test like `ArtworkScalingTest`)
  because `selectArtworkSrc` operates on real `org.json.JSONObject`s, which the Android unit-test
  classpath otherwise stubs to throw "not mocked". The `parseMaxEdge` assertions remain pure logic;
  only the harness changed.

**Deferred (seeds for the next critic)**

- **R-C** — handler-map threading: `actionHandlers`/`customActions` are plain `HashMap`/`LinkedHashMap`
  mutated on the bridge thread but `actionHandlers.keySet()` is also iterated when building
  `supportedActions`; consider confining all handler-map access to one thread or guarding it.
- **F-A** (was F5) — `onCustomCommand` args payload still dropped; no per-tap data reaches JS.
- **U-B** — no state read-back / events on the JS side; an example custom-action demo in `example/`
  would exercise the round trip.
- **R3 (carried, narrowed)** — `blob:` artwork URLs remain unsupported on Android (the branch now
  cleanly clears rather than no-ops, but real blob decoding is still TODO).

### Iteration 4 — handler-map thread-confinement + example/README custom-action demo

**Shipped** (addresses deferred **R-C** handler-map threading + **U-B** example custom-action demo)

Confined ALL `actionHandlers`/`customActions` access **and** the kept-alive `PluginCall`
resolve/release/setKeepAlive lifecycle to the MAIN looper, collapsing handler registration and the
player/layout publish into a single main-looper turn. The Media3 session/player continue to be
touched only on the main looper. **No public TS or native signature changes; standard-action
behaviour is byte-for-byte identical** (same ops, relocated to one thread). Before this change the
maps were mutated on the Capacitor bridge thread while `actionHandlers.keySet()` was also iterated
there to build `supportedActions` — a read/write straddling two threads (`updateServiceState`'s
snapshot vs. the bridge-thread writers), and registration vs. publish could interleave.

- **R4/R6 — `setActionHandler` is now a thin bridge-thread prologue.** It validates `action`
  (rejecting on the bridge thread as before), extracts `removeHandler`/`label`/`icon` + the
  `PluginCall` into locals, calls `call.setKeepAlive(true)` **on the bridge thread for the non-remove
  case** (establishing the RETURN_CALLBACK contract before the call escapes threads — matches the
  existing `setKeepAlive` expectation), then `mainHandler.post(() -> applyActionHandler(...))`. The
  remove case no longer resolves on the bridge thread; resolution moved into the runnable.
- **R6 — new `applyActionHandler(...)` (main looper)** owns the whole previous body: previous-call
  release, `customActions` remove/put, `actionHandlers.put`, then publishes player state + custom
  layout **inline on main** (no further post). For the remove branch it `call.resolve()`s here. This
  collapses registration + publish into one main-looper turn (fixes the register/publish interleave).
- **`pushPlayerState()` extracted from `updateServiceState()`.** `pushPlayerState()` assumes it is
  already on main and now takes the `actionHandlers.keySet()` → `supportedActions` snapshot **inside
  it** (previously taken on the bridge thread before the post). `updateServiceState()` is now just
  `mainHandler.post(this::pushPlayerState)`. `applyActionHandler` and the `setMetadata` artwork
  runnable (already on main) call `pushPlayerState()` directly to avoid an extra hop.
- **`updateCustomLayout()` no longer posts.** It snapshots `customActions.values()` inline on main
  (its callers — `applyActionHandler`, `onServiceConnected` — already run there) and hands the
  snapshot to `service.updateCustomActions(...)` (itself unchanged; still posts internally).
- **R5 — `actionCallback(String, JSObject)` does a single guarded `get`.** `PluginCall call =
  actionHandlers.get(action); if (call != null && !CALLBACK_ID_DANGLING.equals(call.getCallbackId())
  && !call.isReleased()) { ... call.resolve(data); }` — one `get` (no TOCTOU on one thread), folding
  in the `isReleased()` guard so a tap arriving after the stored call was released is dropped.
- **`hasActionHandler` gained `&& !call.isReleased()`.** Both `actionCallback` signatures retained.
- Brief Javadoc added on `actionHandlers`/`customActions`/`actionCallback`/`hasActionHandler`/
  `pushPlayerState` documenting the main-looper confinement.

**U5 — example + README custom-action demo**

- `example/src/js/media-session.js` — added a self-re-registering `like` toggle (`let liked = false;`
  + `registerLike()` helper, called once in setup) using only the public `{action,label,icon}`
  surface; flips `Like`/`heart` ↔ `Unlike`/`heart-filled` from inside its own handler.
- `README.md` — hand-written "Custom actions (Android)" subsection under `## Usage`, ABOVE the
  `<docgen-index>` block (docgen only manages the `<docgen-index>`/`<docgen-api>` blocks, so the prose
  survives `npm run build` — verified the build only touched those 17 added lines).

**Files changed**

- `android/.../MediaSessionPlugin.java` — thin `setActionHandler` prologue + new
  `applyActionHandler`; `pushPlayerState()` split out of `updateServiceState()` with the `keySet()`
  snapshot moved onto main; inline `updateCustomLayout()`; single-`get` guarded `actionCallback`;
  `hasActionHandler` `isReleased()` guard; confinement Javadoc; `setMetadata` artwork runnable now
  calls `pushPlayerState()` inline.
- `android/.../MediaSessionPluginTest.java` — `times` import; idle added to three pre-existing
  no-idle call-sites (`hasActionHandlerIsFalseForDanglingCallback`,
  `actionCallbackIgnoresUnregisteredActions`, `actionCallbackAddsActionToData`) now that put/remove
  happen in a posted runnable; 5 new confinement tests.
- `example/src/js/media-session.js`, `example/tests/media-session.test.js` (like toggle + 1 test).
- `README.md` — hand-written custom-actions subsection (outside docgen blocks).

**Test cases added** (Android +5, example +1)

- `MediaSessionPluginTest` (38 → 43): `removedHandlerThenTapDoesNotResolveReleasedCall` (R5 — remove
  releases the stored call, a later tap resolves nothing, handler gone);
  `actionCallbackDropsReleasedCallStillInMap` (exercises the `isReleased()` guard branch directly with
  a still-mapped but released call); `registerThenImmediateTapResolvesExactlyOnce` (R4/R6 —
  `times(1)` resolve with `action=="play"`); `registrationAndLayoutPublishSettleConsistently` (R6 —
  after ONE idle, both `hasActionHandler("like")` and the session custom layout reflect the `like`
  button); `reRegisterStillReleasesPreviousCallOnMain` (tap between two registrations; the released
  first call is never resolved by the post-swap tap, the second resolves once).
- Audit note: all other `setActionHandler` call-sites already idle via helpers
  (`registerActionHandlers`, `setPlaybackState`) or a following `setPlaybackState`; the missing-action
  reject test stays synchronous (no idle needed).
- `media-session.test.js` (14 → 15): the `like` toggle re-registers with `Unlike`/`heart-filled`
  after the handler fires; the existing "registers handlers for all actions" containment test still
  passes.

**Verification — all gates green**

- Web: `npm run build` → clean + docgen + tsc + rollup all succeeded (exit 0); README regenerated and
  the hand-written custom-actions prose survived (only the 17 added lines changed).
- Android: `cd android && ./gradlew test` → `BUILD SUCCESSFUL`; **98 tests, 0 failures / 0 errors**
  (ArtworkScalingTest 5, ArtworkSelectionTest 13, MediaSessionPluginTest 43, MediaSessionServiceTest 9,
  WebViewProxyPlayerTest 28).
- Example: `cd example && npm test` (vitest) → **15 tests passed** (1 file). `npm install` was run
  first (dev-only vitest/jsdom; plugin is `file:..`).

**Deviation from plan** — none of substance. (Test helper `mockActionHandlerCall` already stubs
`isReleased()→false`, so the new tests stub `isReleased()→true` after the simulated release to drive
the guard; the production release path itself is exercised via `verify(...).release(any())`.)

**Deferred (seeds for the next critic)**

- **NOT settling the register promise (DEFERRED to iteration 5).** The kept-alive RETURN_CALLBACK
  call is the *same* call that delivers every tap; settling it correctly (so the register `Promise`
  resolves once on registration while the call stays live for taps) needs its own slice. This
  iteration leaves the register path keep-alive (does not resolve on register).
- **F-A / F5 (carried)** — `onCustomCommand` args payload still dropped; no per-tap data reaches JS
  (the `args` Bundle in `MediaSessionService.onCustomCommand` is unused; handler still gets only
  `{action}`).
- **U4 (carried/expanded)** — full `addListener`/`getState` JS-side state read-back + events, AND the
  register-promise-settling above, remain open.
- **F3 (carried)** — arbitrary bitmap icons for custom actions (still limited to the curated built-in
  `CommandButton.ICON_*` set; no `iconUri`/custom drawable threading).
- **R3 (carried)** — `blob:` artwork URLs still unsupported on Android (decoding TODO).

### Iteration 5 — read-back getters + addListener('action') event channel + custom-action args payload + destroyed-guard

**Shipped** (addresses deferred **U4** read-back/events, **F-A/F5** custom-action args, plus a cheap
**R-3** destroyed guard). ADDITIVE ONLY — the kept-alive `setActionHandler(options, handler)` per-tap
delivery is byte-for-byte unchanged; the register-promise is still NOT settled on Android (settling it
would break per-tap delivery — explicitly out of scope). No existing method signatures changed.

- **U-1 — `addListener('action', cb)` event channel.** `actionCallback(String, JSObject)` now, AFTER
  the existing guarded kept-alive handler resolve, ALSO emits `notifyListeners("action", event)`
  **unconditionally** — so a listener fires on EVERY action (standard + custom) even when no
  `setActionHandler` handler is registered. The emitted `event` is a fresh `JSObject` copy of `data`
  (built by iterating `data.keys()` + `data.opt(key)`) with `action` set, so it does not alias the
  exact `JSObject` handed to `call.resolve(data)`. Stays on the main looper (`actionCallback`'s
  confinement thread). `addListener` itself is inherited from Capacitor's base `Plugin` (no override);
  TS adds the `addListener(eventName: 'action', ...)` overload + imports `PluginListenerHandle` from
  `@capacitor/core`. The `index.ts` Proxy forwards `addListener` + getters untouched via `Reflect.get`.
- **U-1 — read-back getters (`getMetadata`/`getPlaybackState`/`getPositionState`).** New
  `@PluginMethod` (RETURN_PROMISE) getters resolving the cached last-set values:
  `getPlaybackState` → `{playbackState}`; `getMetadata` → `{title,artist,album}` (artwork OMITTED —
  only decoded bytes are cached natively); `getPositionState` → `{duration,position,playbackRate}`.
  Documented SAME-BRIDGE-THREAD invariant: they run on the bridge thread and read only fields WRITTEN
  on the same bridge thread by the setters (NOT the main-written `artworkData`), so no synchronization.
  Web getters read minimal caches (`metadataCache`/`playbackStateCache`/`positionStateCache`, MERGE for
  metadata/position so omitted fields preserve, assign for playback state), enriched from
  `navigator.mediaSession` where present (metadata fields; playbackState fallback when still `none`).
- **F-1 — custom-action args payload.** New `MediaSessionService.bundleToJSObject(Bundle)` marshals a
  command's args `Bundle` into a `JSObject` (String/boolean/int/long/double/float overloads; null
  values skipped; `String.valueOf(...)` fallback). `onCustomCommand` now passes
  `bundleToJSObject(args)` (folding in any non-empty `sessionCommand.customExtras`, with the controller
  args taking precedence) instead of `new JSObject()` → flows through
  `plugin.actionCallback(customAction, data)` → both the kept-alive handler and the new listener →
  surfaces as `ActionDetails.data` in JS. TS: added `data?: { [key: string]: any }` to `ActionDetails`.
  No `actionCallback` signature change.
- **R-3 — cheap destroyed guard.** New `private volatile boolean destroyed = false;`. Checked
  (`if (destroyed) return;`) at the top of the `setMetadata` main-post body AND the artwork
  result-delivery runnable, so a teardown between post and run drops work that would touch a released
  player/state. `handleOnDestroy` now sets `destroyed=true` and calls
  `mainHandler.removeCallbacksAndMessages(null)` BEFORE `artworkExecutor.shutdownNow()`/`stopMediaService()`.

**Files changed**

- `src/definitions.ts` — `PluginListenerHandle` import; `ActionDetails.data`; three getter signatures
  + `addListener('action')` overload (full JSDoc); `setActionHandler` JSDoc appended with the
  Android keep-alive / don't-await note.
- `src/web.ts` — three caches + getter implementations; `setActionHandler` wraps the user handler so
  the navigator handler ALSO `notifyListeners('action', ...)` (user handler still fires first); the
  iter-1 try/catch + iter-2 standard-action gate intact; `toActionDetails` mapper.
- `src/index.ts` — comment only (verified `addListener`/getters forward via `Reflect.get`).
- `android/.../MediaSessionPlugin.java` — three getter `@PluginMethod`s; unconditional additive
  `notifyListeners("action", ...)` in `actionCallback`; `destroyed` field + guards + `handleOnDestroy`
  ordering.
- `android/.../MediaSessionService.java` — `bundleToJSObject`; `onCustomCommand` marshals args+extras.
- `android/.../MediaSessionPluginTest.java`, `MediaSessionServiceTest.java` — new tests (below).
- `example/src/js/media-session.js` — `addListener('action')` + `getPlaybackState()` readback demo.
- `example/tests/media-session.test.js` — extended the `vi.mock` (option A) with `addListener` +
  getter mocks; +2 tests.
- `README.md` — regenerated by docgen (new getters, `addListener` overload, `ActionDetails.data`); the
  hand-written "Custom actions (Android)" prose survived and a new hand-written "Listening for actions
  and reading state back" subsection was added above `<docgen-index>`.

**Test cases added** (Android +12, example +2)

- `MediaSessionPluginTest` (43 → 52): `getPlaybackStateReturnsCachedState`,
  `getMetadataReturnsCachedTextFields` (asserts `has("artwork")` false), `getPositionStateReturnsCachedValues`,
  `getPositionStateReturnsDefaultsBeforeAnySet` (0/0/1 preserve/default case), `addListenerReceivesActionEvent`
  (listener + separate handler both fire; handler still `times(1)`), `addListenerReceivesActionEventWithNoHandlerRegistered`
  (event fires with NO handler), `actionEventCarriesCustomArgs` (data int/bool/string surfaced),
  `destroyedGuardDropsLateArtwork` (R-3: latch-blocked fetcher, `handleOnDestroy()`, release+idle, no NPE,
  service torn down), `existingHandlerStillResolvesExactlyOnceWithListenerRegistered`. New helper
  `mockListenerCall(eventName)` (stubs the base-Plugin `addListener` contract on a SEPARATE mock from the
  handler mock, so existing `times(1)` handler-resolve assertions still hold).
- `MediaSessionServiceTest` (9 → 12): `onCustomCommandMarshalsArgsToPlugin`
  (`setPlugin(mock)`, drive `onCustomCommand` with a String+boolean+int bundle, `verify(mockPlugin).actionCallback(eq("like"), captor)`,
  assert marshaled keys/types, future yields RESULT_SUCCESS), `bundleToJSObjectCoversCommonTypes`
  (String/boolean/int/long/double/float + null-skip), `bundleToJSObjectHandlesNullBundle`.

**Verification — all three gates green**

- Web (root build): `npm run build` → clean + docgen + tsc + rollup all succeeded (exit 0); README
  regenerated, both hand-written prose subsections survived.
- Android: `cd android && ./gradlew test` → `BUILD SUCCESSFUL`; **110 tests, 0 failures / 0 errors**
  (ArtworkScalingTest 5, ArtworkSelectionTest 13, MediaSessionPluginTest 52, MediaSessionServiceTest 12,
  WebViewProxyPlayerTest 28).
- Example: `cd example && npm test` (vitest) → **17 tests passed** (1 file); deps already installed.

**Deviation from plan** — minor: the `destroyedGuardDropsLateArtwork` test does NOT assert
`awaitArtworkIdle(...)` after `handleOnDestroy()`, because `handleOnDestroy` calls
`artworkExecutor.shutdownNow()`, after which submitting the barrier task would be rejected (returns
false). The test instead releases the gate, idles the main looper and asserts no exception + the
service was torn down (`service == null`) — which is exactly the R-3 guarantee. JSDoc avoids `{@link}`
cross-references in the new interface docs because this docgen version renders them literally in the
README; plain backticked prose is used instead (consistent with the rest of the file).

**Deferred (seeds for the next critic)**

- **R-1** — service-confinement: `MediaSessionService` session/layout mutation and the plugin's
  main-looper confinement are separate disciplines; a single explicit confinement contract across the
  plugin↔service boundary is still informal.
- **F-3 (carried)** — arbitrary bitmap icons for custom actions (`iconUri`/custom drawable threading;
  still limited to the curated built-in `CommandButton.ICON_*` set).
- **R3 (carried)** — `blob:` artwork URLs still unsupported on Android (decoding TODO).
- **R-2** — service teardown debounce: rapid `playing`↔`none` transitions bind/unbind/stop the service
  repeatedly with no debounce; a short settle window would avoid churn.

### Iteration 6 — binding-state main-looper confinement + teardown debounce + foregroundService docs

**Shipped** (addresses deferred **R-1** binding-state confinement + **R-2** teardown debounce, plus
a **U-1** docs gap). Android + README ONLY — no TS/`web.ts`/`index.ts`/example change, no public
method signature change. The bind/teardown decision moved off the bridge thread onto the main looper
and is now debounced; the `setPlaybackState` promise still resolves immediately (independent of the
bind outcome).

- **R-1 — `service`/`serviceBindingRequested` are now strictly MAIN-LOOPER-CONFINED.** Dropped the
  reliance on these being touched from the bridge thread; both fields are now mutated/read only on the
  main looper, so the check-then-bind in `applyPlaybackState` (`if (service == null) startMediaService()`)
  is atomic w.r.t. `onServiceConnected`/`onServiceDisconnected` (which Android already delivers on
  main) without `volatile`. Added confinement Javadoc on both fields (mirroring the `actionHandlers`
  doc) and a "MUST be called on the main looper" line on `startMediaService`/`stopMediaService` (their
  bodies are unchanged; they no longer post — callers are responsible for being on main).
  - `setPlaybackState` (bridge thread) rewritten to the `setMetadata` pattern: write `playbackState`
    on the bridge thread, capture `final boolean playback`, `mainHandler.post(() ->
    applyPlaybackState(playback))`, then `call.resolve()` immediately on the bridge thread (resolve
    does not depend on the bind outcome).
  - New `applyPlaybackState(boolean)` (main looper): `if (destroyed) return;`; on `playback`
    cancel any pending teardown, bind if `service == null`, then `pushPlayerState()` INLINE (replacing
    the old `updateServiceState()` post — one deterministic main-looper turn; when the service is not
    bound yet `pushPlayerState` early-returns and `onServiceConnected` pushes once connected, as
    before); on non-playing in during-playback-only mode `scheduleServiceTeardown()` (was an immediate
    stop); else (`always` mode) `pushPlayerState()`.
  - `load()` now defers the `always`-mode startup by one looper turn (`mainHandler.post(this::startMediaService)`)
    so EVERY mutation of `service`/`serviceBindingRequested` is literally main-looper.
  - Audited every reader/writer ends up on main: `load` (posts), `applyPlaybackState` (posted),
    `startMediaService`/`stopMediaService` (assume main), `onServiceConnected`/`onServiceDisconnected`
    (already main), `pushPlayerState`/`updateCustomLayout` reads (already main), `handleOnDestroy`
    (main). Media3 session/player stays main-only (unchanged).

- **R-2 — debounced service teardown.** New main-confined `Runnable pendingServiceTeardown` field and
  `static final long SERVICE_TEARDOWN_DELAY_MS = 750` (Javadoc: settle window absorbing between-tracks
  `'none'`). `scheduleServiceTeardown()` (main): if a teardown is already pending it KEEPS the existing
  timer (first `'none'` starts the clock; does NOT reset — prevents starvation); else posts a delayed
  runnable that nulls the field then `stopMediaService()`. `cancelPendingServiceTeardown()` (main):
  removes the callback and nulls the field; no-op when nothing is pending. A `'playing'`/`'paused'`
  cancels a pending stop BEFORE the `service == null` check, so `none → playing` within the window
  keeps the binding (no unbind/rebind). `'always'` mode never schedules teardown (the else-if is never
  taken). `handleOnDestroy` reordered: `destroyed = true;` → `removeCallbacksAndMessages(null);` →
  `cancelPendingServiceTeardown();` (defensive field clear) → `artworkExecutor.shutdownNow();` →
  `stopMediaService();` (immediate, unconditional) → `super`. Teardown is never deferred on destroy.

- **U-1 — documented `foregroundService`.** Hand-written "Configuration (Android)" subsection added to
  `README.md` ABOVE `<docgen-index>` (after "Listening for actions and reading state back"), so it
  survives docgen. Documents the Capacitor config key `foregroundService` under the `MediaSession`
  plugin block: `'always'` = service/MediaSession started at plugin load and kept alive regardless of
  playback (session/notification present before the first `setPlaybackState('playing')`); default
  (absent/other) = during-playback only (starts on `'playing'`/`'paused'`, stops shortly after it
  settles to a non-playing state — the brief settle delay is called out so R-2 is documented). Includes
  the JSON snippet `{ "plugins": { "MediaSession": { "foregroundService": "always" } } }`. No
  `definitions.ts`/docgen change (config keys are not plugin methods).

**Files changed**

- `android/.../MediaSessionPlugin.java` — R-1 field confinement Javadoc (`service`/`serviceBindingRequested`)
  + "MUST be on main" docs on `startMediaService`/`stopMediaService`; deferred `always` startup in
  `load()`; rewritten `setPlaybackState` + new `applyPlaybackState`; R-2 `pendingServiceTeardown` /
  `SERVICE_TEARDOWN_DELAY_MS` + `scheduleServiceTeardown`/`cancelPendingServiceTeardown`; reordered
  `handleOnDestroy`.
- `android/.../MediaSessionPluginTest.java` — `idleMainFor(ms)` (ShadowLooper `idleFor`) + `setPlaybackStateNoIdle`
  helpers; a `SERVICE_TEARDOWN_DELAY_MS()` reflection reader; setUp registers the real binder via
  `ShadowApplication.setComponentNameAndServiceForBindService(...)` so a real rebind after teardown
  drives `onServiceConnected` with the live binder; two migrated teardown tests + 7 new R-2 tests.
- `README.md` — hand-written "Configuration (Android)" subsection (outside docgen blocks; build only
  touched the 17 added lines, docgen blocks unchanged).

**Test cases added/migrated** (Android +7 net; MediaSessionPluginTest 52 → 59)

- Migrated `setPlaybackStateNoneStopsServiceDuringPlaybackOnlyMode`: asserts `service` still non-null
  immediately after `'none'` (window not advanced), then `idleMainFor(SERVICE_TEARDOWN_DELAY_MS+50)`
  and asserts `service == null`.
- Migrated `setPlaybackStateNoneKeepsServiceInAlwaysMode`: adds `idleMainFor(1000)` after `'none'` and
  asserts `service` still non-null (no teardown ever).
- New: `playingNoneThenPlayingWithinWindowKeepsServiceBound` (none→playing within window keeps the SAME
  instance, no rebind, `pendingServiceTeardown == null`, player READY/playWhenReady);
  `settledNoneTearsDownServiceAfterDelay`; `repeatedNoneDoesNotResetTeardownWindow` (400+400 cumulative
  from FIRST none → torn down); `singleBindSingleUnbindNoIllegalArgument` (playing→none→playing→none
  with window advances; no `IllegalArgumentException`, final `service == null` /
  `serviceBindingRequested == false` / `getBoundServiceConnections().size() == 0`);
  `alwaysModeNeverSchedulesTeardown` (`pendingServiceTeardown == null` after a long advance);
  `handleOnDestroyCancelsPendingTeardownAndStopsImmediately` (destroy with a pending teardown →
  immediate `service == null`, `pendingServiceTeardown == null`, later advance no double-stop);
  `playbackStateBindDecisionRunsOnMainLooper` (resolve() before idle, `service` stays null until the
  posted `applyPlaybackState` runs — after a settled teardown to start from a null/unbound state).

**Verification — both required gates green**

- Web (root build): `npm run build` → clean + docgen + tsc + rollup all succeeded (exit 0); README
  regenerated, the hand-written Configuration prose survived (only the 17 added lines changed). Example
  vitest not run (no TS/example change).
- Android: `cd android && ./gradlew clean test` → `BUILD SUCCESSFUL`; **117 tests, 0 failures / 0
  errors** (ArtworkScalingTest 5, ArtworkSelectionTest 13, MediaSessionPluginTest 59,
  MediaSessionServiceTest 12, WebViewProxyPlayerTest 28).

**Deliberate compat delta**

- In default (during-playback-only) mode, a non-playing state no longer stops the service IMMEDIATELY:
  there is now a **~750 ms settle delay** (`SERVICE_TEARDOWN_DELAY_MS`) before teardown. This is the
  intended R-2 behaviour (absorbs the between-tracks `'none'`), documented in the README. A
  `none → playing` within that window keeps the existing binding (previously it unbound then rebound).
  `'always'` mode and the steady-state single-push-per-call behaviour are unchanged. Teardown on
  `handleOnDestroy` remains immediate (never deferred).

**Deviation from plan** — minor test-scaffolding additions only. (1) Added
`ShadowApplication.setComponentNameAndServiceForBindService(...)` in `setUp` because the existing
scaffolding wired the initial connection manually (bypassing `bindService`); a real rebind after a
debounced teardown needed Robolectric's `bindService` to deliver the live binder rather than its
default null binder. (2) `singleBindSingleUnbindNoIllegalArgument` corroborates with
`getBoundServiceConnections().size() == 0` only AFTER the final teardown (the initial
manually-wired connection is not tracked by `bindService`, so a mid-playback "== 1" assertion is not
meaningful with this scaffolding); the bind/unbind correctness is otherwise asserted via
`service`/`serviceBindingRequested`. (3) `playbackStateBindDecisionRunsOnMainLooper` (the plan's
optional test #7) first settles a teardown so `service` starts null (setUp leaves it non-null),
making "service stays null until idle" observable.

**Deferred (seeds for the next critic)**

- **F-3 (carried)** — arbitrary bitmap icons for custom actions (`iconUri`/custom drawable threading;
  still limited to the curated built-in `CommandButton.ICON_*` set).
- **F-4 / R-3 (carried)** — `blob:` artwork URLs still unsupported on Android (decoding TODO); no
  artwork-failure signal surfaced to JS.
- **U-2** — Android `getMetadata` omits artwork (only decoded bytes are cached natively; the original
  artwork array / a data-URL round-trip is not returned), an asymmetry vs. the web getter that could be
  documented or closed.

### Iteration 7 — custom-action iconUri + enabled + getMetadata artwork array (closes U-2)

**Shipped** (addresses deferred **F-3** custom-drawable `iconUri` threading [now **F-1**], a new
per-action **F-2** `enabled` flag, and **U-2** Android `getMetadata` artwork asymmetry). ADDITIVE +
backward-compatible. Android + `definitions.ts` JSDoc only — no `web.ts`/`index.ts`/`example` change,
no existing method-signature change on the public TS surface.

- **F-1 — custom-action `iconUri`.** New optional `ActionHandlerOptions.iconUri?: string` (Android
  only; no-op Web/iOS): a custom drawable URI (e.g. `content://`/`android.resource://`/`file://`).
  `iconUri` takes precedence over `icon` for the button's appearance on modern controllers, while the
  `icon` constant is retained as a fallback. `CustomActionSpec` widened with `@Nullable final String
  iconUri` (kept as a RAW URI string so `CustomActions` stays free of `android.net.Uri`); parsed via
  `Uri.parse` only in `MediaSessionService.buildButton`, where `builder.setIconUri(...)` layers it on
  top of the icon-constant builder. `MediaSessionPlugin.setActionHandler` reads
  `call.getString("iconUri")` into a final on the bridge thread and threads it through
  `applyActionHandler` → `new CustomActionSpec(action, label, iconConstant, iconUri, enabled)`
  (customActions stays main-looper-confined).
- **F-2 — per-action `enabled`.** New optional `ActionHandlerOptions.enabled?: boolean` (Android only;
  defaults `true`; a disabled button renders greyed/non-interactive with its layout slot kept).
  `CustomActionSpec` widened with `final boolean enabled`; `buildButton` calls `.setEnabled(spec.enabled)`
  (was hard-coded `true`). `setActionHandler` reads `call.getBoolean("enabled", true)` into a final and
  threads it through like `iconUri`.
- **U-2 — `getMetadata` returns the artwork array.** New bridge-thread-confined field
  `private JSArray artworkMetadata = null` (the RAW array as supplied to `setMetadata`, mirroring what
  the web getter returns — NOT the main-written decoded `artworkData`; written and read only on the
  Capacitor HandlerThread, so no sync). `setMetadata` mirrors the iter-3 absent/present cache semantics:
  an ABSENT `artwork` key (`getArray` → `null`) PRESERVES the cached array; a PRESENT key (incl. an
  empty array) sets `this.artworkMetadata = artworkArray` on the bridge thread BEFORE the
  `mainHandler.post`. `getMetadata` now adds `if (artworkMetadata != null) result.put("artwork",
  artworkMetadata)` after the text fields (the decoded `artworkData` is still never read here —
  confinement preserved). The key is omitted only when no artwork has ever been set.

**Deviation from plan**

- `MediaSessionService.buildButton` additionally sets a fallback legacy icon resource
  (`setCustomIconResId(android.R.drawable.ic_menu_more)`) when an `iconUri` is supplied WITHOUT a
  built-in icon constant. Media3 1.4.1's legacy `PlaybackStateCompat.CustomAction.Builder` REQUIRES a
  non-zero `iconResId`; a button with only an `iconUri` has `iconResId == 0` (the no-arg builder yields
  0, and `CommandButton.Builder(int)` derives `iconResId` from the icon constant, which is 0 for
  `ICON_UNDEFINED`), so granting commands to a legacy controller threw
  `IllegalArgumentException: You must specify an icon resource id to build a CustomAction` inside
  `MediaSession.setAvailableCommands` — surfaced by the two iconUri-only tests (#1, #5). `setCustomIconResId`
  populates `iconResId` directly and leaves `button.icon` as `ICON_UNDEFINED` and `button.iconUri`
  carrying the real URI, so the test assertions (and the "iconUri takes precedence, icon constant kept
  as fallback" contract) hold; the fallback only affects the otherwise-invalid legacy path.

**Files changed**

- `src/definitions.ts` — `ActionHandlerOptions.iconUri`/`enabled` (with JSDoc); `getMetadata` JSDoc
  updated (Android now returns the original artwork array verbatim from cache, key omitted only when no
  artwork has ever been set).
- `android/.../CustomActions.java` — `CustomActionSpec` widened with `@Nullable final String iconUri`
  + `final boolean enabled` (constructor + `androidx.annotation.Nullable` import).
- `android/.../MediaSessionService.java` — `android.net.Uri` import; `buildButton` `setIconUri`/
  `setEnabled(spec.enabled)` + the `FALLBACK_CUSTOM_ICON_RES_ID` legacy-path guard.
- `android/.../MediaSessionPlugin.java` — `setActionHandler` reads `iconUri`/`enabled` finals;
  `applyActionHandler` signature + `CustomActionSpec` construction threaded; new bridge-thread-confined
  `artworkMetadata` field; `setMetadata` caches the raw array (present-key) / preserves it (absent-key);
  `getMetadata` returns it.
- `README.md` — regenerated by docgen (new `ActionHandlerOptions` `iconUri`/`enabled` rows +
  `getMetadata` text); hand-written prose subsections intact (build touched only the docgen-managed
  blocks).
- `android/.../MediaSessionPluginTest.java` — `mockCustomActionHandlerCall(action,label,icon)` extended
  to stub `getString("iconUri")→null` and `getBoolean(eq("enabled"), any())→true`; new overload
  `mockCustomActionHandlerCall(action,label,icon,iconUri,enabled)`; +7 tests; `org.json.JSONArray` import.

**Test cases added** (Android +7; MediaSessionPluginTest 59 → 66)

- `customActionIconUriIsPublishedOnButton` (button 0 `iconUri != null`, String.valueOf equals
  `content://app/heart.png`); `customActionIconConstantIsFallbackWhenNoIconUri` (`button.icon ==
  ICON_HEART_UNFILLED` AND `button.iconUri == null`); `customActionEnabledDefaultsTrue`
  (`button.isEnabled == true`); `customActionDisabledButtonReflectsFlag` (`enabled:false` →
  `button.isEnabled == false`, button still present); `reRegisterToggleUpdatesIconUriAndEnabled`
  (re-register `Like`/`content://a`/enabled → `Unlike`/`content://b`/disabled: single button,
  displayName "Unlike", iconUri "content://b", isEnabled false, `verify(first).release(any())`);
  `getMetadataReturnsArtworkArray` (2-entry array → `has("artwork")`, length 2, entry 0 src matches);
  `getMetadataPreservesArtworkWhenKeyAbsent` (set array, then text-only `getArray→null`, then
  getMetadata still returns the array).
- `getMetadataReturnsCachedTextFields` unchanged and still green: its set-call stubs
  `getArray("artwork")→null`, so no artwork is ever cached and `assertFalse(result.has("artwork"))`
  holds (absent key → omitted).

**Verification — both required gates green**

- Web (root build): `npm run build` → clean + docgen + tsc + rollup all succeeded (exit 0); README
  regenerated (new `ActionHandlerOptions` rows + `getMetadata` text), hand-written prose intact.
  Example vitest not run (no TS/example change).
- Android: `cd android && ./gradlew test` → `BUILD SUCCESSFUL`; **124 tests, 0 failures / 0 errors**
  (ArtworkScalingTest 5, ArtworkSelectionTest 13, MediaSessionPluginTest 66, MediaSessionServiceTest 12,
  WebViewProxyPlayerTest 28).

**Deferred (seeds for the next critic)**

- **R-1** — `data:`-URI artwork robustness: the base64 detection (`url.indexOf(";base64,")`) and the
  `http`/`blob`/base64 branch ordering in `urlToArtworkData` are loose; a `data:` URI without
  `;base64,` (percent-encoded) or an odd scheme is silently dropped.
- **R-2** — `HttpURLConnection` hardening / OOM: no max response size / content-length guard before
  `BitmapFactory.decodeStream`, no redirect/cap policy; a large or malicious image could OOM the
  artwork executor thread.
- **F-4 / R-3 (carried)** — `blob:` artwork URLs still unsupported on Android (decoding TODO); no
  artwork-failure signal surfaced to JS.
- **U-3** — no `example/` demo exercising the new `iconUri`/`enabled` custom-action options (the example
  still only toggles `label`/`icon`); a demo would exercise the round trip end-to-end.

### Iteration 8 — artwork-fetch robustness (data: URIs + HTTP hardening/downsample) + example demo + playbackRate double

**Shipped** (addresses deferred R-1, R-2, U-3, and U-1 playbackRate precision)

- **R-1 — `data:` URI robustness.** `urlToArtworkData` now dispatches on SCHEME
  (`data:`/`blob:`/`http(s)://`), removing the old loose `;base64,` substring routing (which
  misrouted any URL containing that substring and silently dropped/cleared valid percent-encoded
  data URIs). New pure `static byte[] decodeDataUri(String)` parses RFC 2397: split on the first
  comma, token-match the `;base64` flag in the mediatype (so `;charset=base64x` is NOT base64),
  `Base64.decode(DEFAULT)` for base64 bodies else `URLDecoder.decode(…, UTF-8)`; any decode failure
  returns `null` (no exception escapes). Valid percent-encoded data URIs now decode instead of
  clearing the cover.
- **R-2 — HTTP hardening + downsampled decode (OOM fix).** New `httpToArtworkData` checks
  `getResponseCode() == HTTP_OK`, reads the body into a buffer capped at `MAX_ARTWORK_BYTES` (8 MB,
  aborting to `null` if exceeded), and always `disconnect()`s. New shared `bytesToArtworkData(buf,
  len)` does a two-pass `BitmapFactory` decode — pass 1 `inJustDecodeBounds` reads only the bounds,
  then a power-of-two `inSampleSize` (new pure `static int computeInSampleSize(srcW, srcH, maxEdge)`,
  half-dimension loop) downsamples during decode — so a 6000×6000 image no longer allocates ~144 MB
  before `scaleBitmap` shrinks it. Used by BOTH the `data:` and `http(s)` paths.
- **U-1 — `playbackRate` precision.** `setPositionState` now reads `playbackRate` via
  `call.getDouble("playbackRate", this.playbackRate)` (was `getFloat`, which truncated e.g. 1.05).
- **U-3 — example demo.** The example `like` toggle now also passes `iconUri` and `enabled`
  (Android-only; documented as no-op on Web/iOS).

**Files changed**

- `android/.../MediaSessionPlugin.java` — scheme-dispatch `urlToArtworkData`; new `decodeDataUri`,
  `httpToArtworkData`, `bytesToArtworkData`, `computeInSampleSize`; `MAX_ARTWORK_BYTES`;
  `setPositionState` playbackRate→`getDouble`.
- `android/.../ArtworkScalingTest.java` — +6 `computeInSampleSize` cases (now 11).
- `android/.../ArtworkDataUriTest.java` (NEW, Robolectric) — 7 `decodeDataUri` cases.
- `android/.../MediaSessionPluginTest.java` — migrated 4 playbackRate stubs `getFloat`→`getDouble`;
  added an exact-`1.05` round-trip assertion (delta 0.0) to `getPositionStateReturnsCachedValues`.
- `src/definitions.ts` — `setMetadata` JSDoc notes `http(s)://`/`data:` artwork support (large images
  downsampled; `blob:` unsupported on Android). README regenerated by docgen.
- `example/src/js/media-session.js`, `example/tests/media-session.test.js` — `iconUri`/`enabled` on
  the `like` toggle + assertions.

**Verification — all three gates green**

- Web: `npm run build` → exit 0 (docgen + tsc + rollup); README prose preserved.
- Android: `cd android && ./gradlew test` → `BUILD SUCCESSFUL`; **137 tests, 0 failures**
  (ArtworkScalingTest 11, ArtworkSelectionTest 13, ArtworkDataUriTest 7, MediaSessionPluginTest 66,
  MediaSessionServiceTest 12, WebViewProxyPlayerTest 28).
- Example: `cd example && npm test` → **17 tests passed**.

**Deviation from plan**

- The plan/initial implementation added a `Base64.URL_SAFE` retry fallback in `decodeDataUri`. This
  was REMOVED: a `data:` URI's `;base64` payload is standard base64 per RFC 2397/4648, and the
  URL_SAFE retry made genuinely-malformed input decode to garbage under Robolectric's lenient
  shadow, breaking the existing `setMetadataToleratesInvalidArtwork` test (which expects malformed
  base64 to clear the cover). Standard-alphabet-only is both spec-correct and restores strict
  invalid-input handling; the planned `decodesUrlSafeBase64` test case was dropped accordingly.

**Deferred (seeds for the next critic)**

- **R-3** — artwork-fetch failures still silently clear the cover with only a `Log.w`; no
  artwork-error/`artworkLoaded` event surfaced to JS (the iter-5 `notifyListeners` channel exists).
- **Cross-protocol redirect** — `httpToArtworkData` gates on `HTTP_OK`, so a silent http↔https 3xx
  redirect returns `null`; a follow-up could read `Location` and re-open once across protocols with a
  hop cap.
- **F-4 / blob: artwork** — `blob:` URLs still unsupported on Android.
- **U-2** — `ActionDetails.data` is typed `{ [key: string]: any }`; could be tightened.
- **SVG/vector data URIs** — `data:image/svg+xml` decodes to bytes but `BitmapFactory` cannot
  rasterize them (still clears).
