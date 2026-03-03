Technical Design Document: AndroidX Media3 Migration for Capacitor Media Session
================================================================================

1\. Executive Summary
---------------------

This document outlines the architectural migration of the Android implementation of `capacitor-media-session` from the legacy `androidx.media` (`MediaSessionCompat`) framework to the modern `androidx.media3` framework.

The existing implementation relies on deprecated APIs, manual notification construction, and detached session callbacks. Media3 enforces a rigid, predictable architecture where the `MediaSession` is strictly bound to a `Player` interface. This migration will eliminate manual notification management, reduce boilerplate, and align the plugin with current Android OS background execution and media control standards.

2\. Motivation and Architectural Shift
--------------------------------------

**The Problem:** The current implementation in `MediaSessionService.java` manually handles `NotificationManager`, `MediaStyle`, `NotificationCompat.Builder`, and `MediaButtonReceiver`. It is brittle, error-prone, and violates separation of concerns. The OS frequently changes how media notifications are rendered, and manual handling guarantees future compatibility breakage.

**The Media3 Paradigm:** Media3 abstracts the UI and OS-level hooks entirely. A `MediaSessionService` automatically generates the system notification, responds to Bluetooth/hardware media buttons, and manages foreground service lifecycles based strictly on the state of an attached `Player`.

Because the actual media playback occurs within the Capacitor WebView (JavaScript layer) rather than a native Android player (like `ExoPlayer`), we cannot use a standard player implementation. The core of this migration involves constructing a **Proxy Player** that implements the `Player` interface, intercepts Media3 commands, and forwards them across the Capacitor bridge to JavaScript.

3\. System Architecture
-----------------------

### 3.1 High-Level Component Interaction

```
[ JavaScript (WebView) ]
       |     ^
(PluginCall) | (Action Callback)
       v     |
[ MediaSessionPlugin ] <---> [ MediaSessionService ]
       |                            |
(Update State)               (Binds Proxy Player)
       |                            |
       +-------> [ Proxy Player ] <-+
                    (SimpleBasePlayer)

```

1.  **JS -> Native:** JS calls `setMetadata`, `setPlaybackState`, `setPositionState`. The Plugin updates the state of the Proxy Player.

2.  **Native -> JS:** The OS (via Bluetooth, notification, etc.) sends a command to the MediaSession. The session forwards it to the Proxy Player. The Proxy Player triggers a Capacitor plugin callback.

4\. Technical Implementation Details
------------------------------------

### 4.1 Dependency Overhaul

Update `android/build.gradle` to strip legacy dependencies and enforce Media3.

**Remove:**

-   `implementation "androidx.media:media:1.6.0"`

-   `android.support.v4.media.*` imports across all files.

**Add:**

-   `implementation "androidx.media3:media3-session:1.2.0"`

-   `implementation "androidx.media3:media3-common:1.2.0"`

-   *(Note: Explicitly avoid `media3-exoplayer` as we do not handle native playback).*

### 4.2 Service Layer Consolidation (`MediaSessionService.java`)

The current `MediaSessionService` must be rewritten. It will no longer extend `android.app.Service`; it must extend `androidx.media3.session.MediaSessionService`.

**Required Changes:**

1.  **Remove manual notification logic:** Delete `NotificationManager`, `NotificationCompat.Builder`, `MediaStyle`, and `MediaButtonReceiver` implementations.

2.  **Override `onGetSession`:** Return the Media3 `MediaSession` instance.

3.  **Session Lifecycle:** Build the session in `onCreate()` passing the context and the Proxy Player. Release it in `onDestroy()`.

4.  **Binder Modifications:** The custom `LocalBinder` remains necessary to allow `MediaSessionPlugin` to pass state updates to the service/player.

### 4.3 The Proxy Player (`WebViewProxyPlayer.java`)

This is a net-new class. Media3 requires a `Player`. To avoid implementing the massive `Player` interface from scratch, we will extend `androidx.media3.common.SimpleBasePlayer`.

**Responsibilities:**

1.  **State Source of Truth:** Hold the current playback state, position, and metadata provided by the JS layer.

2.  **Command Interception:** Override `handlePlay()`, `handlePause()`, `handleSeek()`, `handleSkipToNext()`, etc.

3.  **Callback Bridging:** Inside these handlers, invoke `plugin.actionCallback("<action>")`.

4.  **Asynchronous Resolution:** Media3 handlers return `ListenableFuture<?>`. Because the actual action happens asynchronously in JS, the native side must return `Futures.immediateVoidFuture()` immediately to acknowledge receipt of the command without blocking the Media3 thread.

### 4.4 State & Metadata Synchronization (`MediaSessionPlugin.java`)

The plugin translates JSON from Capacitor into Media3 State objects.

1.  **Metadata Conversion:** Replace `MediaMetadataCompat` with `androidx.media3.common.MediaMetadata`.

2.  **Artwork Handling:** Media3 prefers raw byte arrays for artwork rather than Bitmaps to optimize IPC (Inter-Process Communication).

    -   Change: Convert resolved `Bitmap` to a compressed `byte[]` and apply via `MediaMetadata.Builder.setArtworkData(byteArray, MediaMetadata.PICTURE_TYPE_FRONT_COVER)`.

3.  **State Updates:** When JS calls `setPlaybackState` or `setPositionState`, the plugin must construct a new `SimpleBasePlayer.State` object and pass it to the Proxy Player. The Proxy Player will call `invalidateState()`, which automatically triggers Media3 to update the system UI (notifications, lock screen).

### 4.5 Manifest Modifications

The `<service>` declaration in `AndroidManifest.xml` must be updated to use the Media3 intent filter.

```
<service
    android:name=".MediaSessionService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>

```

5\. Risk Assessment & Mitigations
---------------------------------

### 5.1 Asynchronous State Desynchronization

**Risk:** JS triggers `setPlaybackState("playing")`, but before the native layer processes it, the user presses "Pause" on their headphones. **Mitigation:** `SimpleBasePlayer` state changes must be atomic. The native Proxy Player must be treated as a *view* of the JS state. Command handlers (e.g., `handlePause`) will fire the callback to JS, but will *not* synchronously change the native player state to "Paused". The state will only change when JS explicitly acknowledges the pause and calls `setPlaybackState("paused")`. This guarantees predictability and prevents race conditions.

### 5.2 Artwork Memory Leaks

**Risk:** Continuously parsing Base64 strings or HTTP URLs into byte arrays for `MediaMetadata` can cause memory bloat. **Mitigation:** Enforce a strict resolution limit on artwork (e.g., scale down to 512x512) before converting to byte arrays. Ensure previous byte arrays are garbage collected upon metadata updates.

### 5.3 Foreground Service Restrictions (Android 14+)

**Risk:** Android 14+ places strict restrictions on starting foreground services from the background. **Mitigation:** Media3 handles foreground service promotion automatically when the player transitions to a playing state. The plugin must remove manual `ContextCompat.startForegroundService` calls from `MediaSessionPlugin.java` and rely entirely on Media3's internal `MediaSessionService` lifecycle management. The config `startServiceOnlyDuringPlayback` will largely be managed by Media3's default behavior.

6\. Detailed Migration Phases
-----------------------------

### Phase 1: Dependency Updates

Update the `android/build.gradle` file to introduce the Media3 framework.

1.  Remove the legacy media dependency: `- implementation "androidx.media:media:1.6.0"`

2.  Add the Media3 dependencies (using the latest stable version):

    -   `+ implementation "androidx.media3:media3-session:1.2.0"`

    -   `+ implementation "androidx.media3:media3-common:1.2.0"`

### Phase 2: Refactoring `MediaSessionService.java`

Media3 provides its own base class for foreground services that handles the heavy lifting for notifications and lifecycle.

1.  **Change Base Class:** Change `MediaSessionService extends Service` to `MediaSessionService extends androidx.media3.session.MediaSessionService`.

2.  **Remove Legacy Notification Code:** Delete the entire `NotificationManager`, `NotificationCompat.Builder`, `MediaStyle`, and `MediaButtonReceiver` implementations. Media3's `MediaSessionService` automatically generates the foreground notification and handles media buttons based on the attached `Player`'s state.

3.  **Implement `onGetSession`:** Override `onGetSession(MediaSession.ControllerInfo controllerInfo)` to return your `MediaSession` instance.

4.  **Session Creation:** In `onCreate()`, build the `MediaSession` using `new MediaSession.Builder(this, player).build()`. The proxy `Player` (created in Phase 3) will be passed here.

5.  **Cleanup:** In `onDestroy()`, ensure that `mediaSession.release()` is called to prevent memory leaks.

### Phase 3: Replacing `MediaSessionCallback.java` with a Proxy `Player`

This is the core of the migration. Media3 drops `MediaSessionCompat.Callback`. The interface must bridge Media3's `Player` interface to the Capacitor plugin.

1.  **Delete `MediaSessionCallback.java`** entirely.

2.  **Create a Proxy Player:** Create a new class extending `androidx.media3.common.SimpleBasePlayer`. This base class handles listener routing and state consistency.

3.  **Intercept Commands:** Override the asynchronous command methods in `SimpleBasePlayer` (e.g., `handlePlay()`, `handlePause()`, `handleSeek()`, `handleSkipToNext()`).

4.  **Trigger JS Callbacks:** Inside these overrides, invoke `plugin.actionCallback("play")`, etc., exactly as previously done in `MediaSessionCallback`.

5.  **Resolve Futures:** Return `Futures.immediateVoidFuture()` for these methods to satisfy the Media3 asynchronous API requirements without blocking the internal Looper.

### Phase 4: State and Metadata Syncing in `MediaSessionPlugin.java`

The JS code currently calls `setMetadata`, `setPlaybackState`, and `setPositionState`. These must be translated into Media3 state updates via the Proxy Player.

1.  **Update Metadata:** Replace `MediaMetadataCompat.Builder` with `androidx.media3.common.MediaMetadata.Builder`.

2.  **Update Artwork:** Instead of `putBitmap`, Media3 expects byte arrays for artwork or a URI. Convert the `Bitmap` to a `byte[]` and use `setArtworkData(byte[], MediaMetadata.PICTURE_TYPE_FRONT_COVER)`.

3.  **Update Playback State:** Replace `PlaybackStateCompat` with constants like `Player.STATE_READY`, `Player.STATE_BUFFERING`, etc.

4.  **Syncing the Proxy Player:** When the Capacitor plugin receives state updates from JS, it must update the proxy `SimpleBasePlayer`'s internal `State` object. When the `State` of the `SimpleBasePlayer` is updated via `invalidateState()`, Media3 will automatically update the system media notification, the lock screen controls, and connected Bluetooth devices.

### Phase 5: AndroidManifest.xml Updates

Because the Service base class has changed, the service declaration must be updated to allow Android to bind to it correctly.

1.  Ensure the `<service>` tag for `MediaSessionService` includes the intent filter required by Media3:

    ```
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>

    ```

2.  Remove any manual declarations of `MediaButtonReceiver` in the manifest, as Media3 takes over default handling of these broadcast events.

7\. Migration Outcomes & Discoveries
------------------------------------

The migration to `androidx.media3` was successfully implemented following the proposed architecture. The following actions were completed:

*   **Dependencies:** Updated `android/build.gradle` to use `androidx.media3:media3-session:1.2.0`, `androidx.media3:media3-common:1.2.0`, and included Guava for `ListenableFuture`. Removed legacy `androidx.media` dependency.
*   **MediaSessionService:** Removed the manual notification builder, MediaStyle, and `MediaButtonReceiver` from `MediaSessionService.java`. Refactored to extend `androidx.media3.session.MediaSessionService` and cleanly bind the new proxy player.
*   **WebViewProxyPlayer:** Replaced `MediaSessionCallback.java` with a new `WebViewProxyPlayer` extending `SimpleBasePlayer`. Intercepted commands are correctly bridged to JS via `PluginCall` and resolved immediately using `Futures.immediateVoidFuture()`.
*   **MediaSessionPlugin Sync:** Updated `MediaSessionPlugin.java` to map JS metadata to `androidx.media3.common.MediaMetadata`, convert `Bitmap` artworks to `byte[]` arrays for `setArtworkData`, map JS state to `Player.STATE_READY`, and manage playback positions utilizing `PositionSupplier`.
*   **Tests:** Fully migrated `MediaSessionPluginTest.java` to evaluate Media3 components, correctly retrieving states using the attached `SimpleBasePlayer`.

### Deviations and Findings

During the implementation, several technical limitations regarding `SimpleBasePlayer.State` required deviations from the initial plan:

1.  **State Instantiation:** In Media3, `SimpleBasePlayer.State` and its builder have restricted access. Consequently, `currentState.buildUpon()` could not be chained directly in the plugin without accessing protected methods. To resolve this, helper methods (`setMetadata`, `setPlaybackState`, `setPositionState`, `setAvailableCommands`) were introduced directly inside `WebViewProxyPlayer` to mutate its internal `State` reference and trigger `invalidateState()`.
2.  **Playlist Enforcement:** Media3 requires that any `State` referencing an active playback position or metadata must contain a valid playlist. When initializing states (especially for position and playback parameters), an empty playlist would trigger an `IllegalArgumentException`. The code was adjusted to inject a stub `MediaItem` ("WebViewMediaItem") dynamically if the playlist was empty to ensure stability.
3.  **Unique Session ID:** Within tests, recreating `MediaSessionService` multiple times caused an `IllegalStateException` due to non-unique session IDs. A timestamp-based session ID (`"WebViewMediaSession-" + System.currentTimeMillis()`) was introduced in the `MediaSession.Builder` to safely support rapid lifecycle testing.
