# capacitor-media-session

Capacitor plugin for Media Sessions on Web, Android and iOS. Just like the [Media Session Web API](https://w3c.github.io/mediasession/) this enables
- customizable media playback notifications (including controls) on iOS, Android and some browsers
- media control using hardware media keys (e.g. on headsets, remote controls, etc.)
- setting media metadata that can be used by the platform UI

This plugin is necessary for Capacitor apps because the Android WebView does not support the Media Session Web API (if you don't need Android support you could just use the Web API directly on Web and iOS). On Web and iOS this plugin is actually just a very thin wrapper around the Web API and uses it directly, only Android needs a native implementation.

Another problem with audio playback (using web standards, e.g. an `<audio>` element) in Capacitor apps on Android is that it does not work reliably in the background. If your app is in the background Android will force your app to go to sleep even if audio is currently playing in the WebView. This plugin also tries to solve this problem by starting a [foreground service](https://developer.android.com/guide/components/foreground-services) for active Media Sessions enabling background playback.

## Install

If you are using Capacitor 6 or 7 just install the latest 4.x version of this plugin using

```bash
npm install @jofr/capacitor-media-session
npx cap sync
```

For Capacitor 5 you can install the 3.x version (`npm install @jofr/capacitor-media-session@3`) instead, for Capacitor 4 you can install the 2.x version of this plugin (`npm install @jofr/capacitor-media-session@2`) and for Capacitor 3 the 1.x version of this plugin (`npm install @jofr/capacitor-media-session@1`).

### Android

The Android implementation is built on [AndroidX Media3](https://developer.android.com/media/media3): the plugin hosts an `androidx.media3.session.MediaSessionService` with a proxy `Player` that mirrors the playback state of the WebView, and Media3 takes care of the media notification, lock screen and Bluetooth controls, hardware media keys and the foreground service lifecycle. The plugin manifest already requests the `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions (the latter is required since Android 14), so no additional setup is needed in your app.

## Usage

The API of this plugin is modeled after the [already widely supported](https://developer.mozilla.org/en-US/docs/Web/API/Media_Session_API#browser_compatibility) Media Session Web API. That way most available documentation for this web standard should be easily adaptable to this Capacitor plugin and it should be easy to use if you are already familiar with it. If your are not yet familiar with the concepts you can [read more about that on MDN](https://developer.mozilla.org/en-US/docs/Web/API/Media_Session_API) or [on web.dev](https://web.dev/media-session/).

__There is one notable difference compared to the Web API__: You have to explicitly set the playback state to `"playing"` (using [`setPlaybackState()`](#setplaybackstate)) for the notification to start showing. You also have to explicitly set action handlers for play/pause (using [`setActionHandler()`](#setactionhandler)) for the controls in the notification to show up and work. For simple cases on the Web platform (e.g. playing audio using an `<audio>` element) the browser detects playback and wires simple actions like play/pause automatically up. Using this plugin you have to wire up the `<audio>` element manually because the plugin cannot detect audio playback in the WebView on Android automatically. There is an example app included in the repository [that shows how to do that](https://github.com/jofr/capacitor-media-session/blob/main/example/src/js/media-session.js).

### Custom actions (Android)

Besides the standard Media Session actions you can register a *custom action* by passing any non-standard action string together with a `label` (and optional `icon`). A common pattern is a toggle that re-registers itself from inside its own handler to flip the label and icon:

```typescript
let liked = false;
const registerLike = () => {
  MediaSession.setActionHandler(
    { action: 'like', label: liked ? 'Unlike' : 'Like', icon: liked ? 'heart-filled' : 'heart' },
    () => { liked = !liked; registerLike(); }
  );
};
registerLike();
```

On Android custom actions render as extra Media3 custom-layout buttons in the media notification; on Web and iOS they are a silent no-op (only the standard actions are supported there).

### Listening for actions and reading state back

In addition to registering a per-action handler with `setActionHandler`, you can listen for **every** action (standard *and* custom) with `addListener('action', ...)`. Unlike `setActionHandler` (whose promise is kept alive on Android), this resolves with a `PluginListenerHandle` you can `await` and later `remove()`. On Android the custom-action arguments are surfaced as `details.data`:

```typescript
const handle = await MediaSession.addListener('action', (details) => {
  console.log('action fired:', details.action, details.data);
});
// later: handle.remove();
```

You can also read the last values you set back from the plugin's own cache (not a live system read) with `getMetadata()`, `getPlaybackState()` and `getPositionState()`:

```typescript
const { playbackState } = await MediaSession.getPlaybackState();
```

### Configuration (Android)

On Android the plugin reads an optional `foregroundService` key from the `MediaSession` plugin block of your Capacitor configuration. It controls when the underlying media service (and therefore the `MediaSession` and its notification) is started:

* `'always'` — the service and `MediaSession` are started at plugin load and kept alive regardless of playback state. The session and its notification are present *before* the first `setPlaybackState('playing')`.
* default (key absent or any other value) — *during-playback only*: the service starts on the first `'playing'`/`'paused'` state and is stopped shortly after playback settles to a non-playing state. A brief settle delay is applied before teardown so a momentary `'none'` between tracks (immediately followed by `'playing'`) does not churn the service binding.

```json
{
  "plugins": {
    "MediaSession": {
      "foregroundService": "always"
    }
  }
}
```

## API

<docgen-index>

* [`setMetadata(...)`](#setmetadata)
* [`setPlaybackState(...)`](#setplaybackstate)
* [`setActionHandler(...)`](#setactionhandler)
* [`getMetadata()`](#getmetadata)
* [`getPlaybackState()`](#getplaybackstate)
* [`getPositionState()`](#getpositionstate)
* [`addListener('action', ...)`](#addlisteneraction)
* [`setPositionState(...)`](#setpositionstate)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### setMetadata(...)

```typescript
setMetadata(options: MetadataOptions) => Promise<void>
```

Sets metadata of the currently playing media. Analogue to setting the
[metadata property of the MediaSession
interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/metadata)
when using the Media Session API directly.

On Android, artwork handling differs from the Web API: only a **single**
`artwork` entry is fetched, selected by its `sizes` (the smallest image at
least ~512px, otherwise the largest available). Fetching is
**asynchronous** — the returned promise resolves before the image has
loaded, and the artwork appears shortly afterwards. Supplying an `artwork`
array whose selected image fails to load **clears** any previously shown
cover, whereas omitting the `artwork` property entirely **preserves** it.

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code><a href="#metadataoptions">MetadataOptions</a></code> |

--------------------


### setPlaybackState(...)

```typescript
setPlaybackState(options: PlaybackStateOptions) => Promise<void>
```

Indicate whether media is playing or not. Analogue to setting the
[playbackState property of the MediaSession
interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/playbackState)
when using the Media Session API directly.

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code><a href="#playbackstateoptions">PlaybackStateOptions</a></code> |

--------------------


### setActionHandler(...)

```typescript
setActionHandler(options: ActionHandlerOptions, handler: ActionHandler | null) => Promise<void>
```

Sets handler for media session actions (e.g. initiated via onscreen media
controls or physical buttons). Analogue to calling [setActionHandler() of
the MediaSession
interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/setActionHandler)
when using the Media Session API directly.

Passing `null` as the handler removes a previously registered handler for
that action.

In addition to the standard Media Session actions, an arbitrary action
string may be passed to register a *custom action*. On Android a custom
action is published as an extra button in the media notification / session
custom layout; the `label` (required for the button to render) and `icon`
options control its appearance. Re-registering the same custom action
replaces the button, and passing `null` removes it. Custom actions are a
silent no-op on Web and iOS, where only the standard actions are
supported.

**Android promise behaviour:** the returned promise does **not** settle on
registration — the underlying call is kept alive to deliver every tap to
`handler`. Do **not** `await` it on Android (it would hang). If you only
want to know an action fired (rather than registering a per-action
handler), prefer `addListener('action', ...)`, whose promise settles
idiomatically.

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code><a href="#actionhandleroptions">ActionHandlerOptions</a></code> |
| **`handler`** | <code><a href="#actionhandler">ActionHandler</a> \| null</code>       |

--------------------


### getMetadata()

```typescript
getMetadata() => Promise<MetadataOptions>
```

Returns the last metadata set via `setMetadata`. This is a read-back of
the plugin's own cached value (the most recently set fields), **not** a
live read of the underlying system media session.

On Web the cache is enriched from `navigator.mediaSession.metadata` when
available. On Android the **text** fields (`title`, `artist`, `album`) are
returned along with the original `artwork` array returned **verbatim** from
the cache (the array as supplied to `setMetadata`, not a re-encoding of the
decoded image bytes); the `artwork` key is omitted only when no artwork has
ever been set.

**Returns:** <code>Promise&lt;<a href="#metadataoptions">MetadataOptions</a>&gt;</code>

--------------------


### getPlaybackState()

```typescript
getPlaybackState() => Promise<PlaybackStateOptions>
```

Returns the last playback state set via `setPlaybackState` (defaults to
`"none"`). This is a read-back of the plugin's own cached value, **not** a
live read of the underlying system media session. On Web the value falls
back to `navigator.mediaSession.playbackState` when no value has been set
yet.

**Returns:** <code>Promise&lt;<a href="#playbackstateoptions">PlaybackStateOptions</a>&gt;</code>

--------------------


### getPositionState()

```typescript
getPositionState() => Promise<PositionStateOptions>
```

Returns the last position state set via `setPositionState` (`duration`,
`position`, `playbackRate`). This is a read-back of the plugin's own
cached value, **not** a live read of the underlying system media session.

**Returns:** <code>Promise&lt;<a href="#positionstateoptions">PositionStateOptions</a>&gt;</code>

--------------------


### addListener('action', ...)

```typescript
addListener(eventName: 'action', listenerFunc: (details: ActionDetails) => void) => Promise<PluginListenerHandle>
```

Adds a listener that fires on **every** media action (standard *and*
custom) the user triggers — system media controls, hardware media buttons
or a custom-action button. This fires **in addition to** any handler
registered for the same action via `setActionHandler`; both are invoked.

Unlike `setActionHandler` (whose promise is kept alive on Android), the
promise returned here settles idiomatically with a `PluginListenerHandle`
you can `await` and later `remove()`. The listener receives the same
`ActionDetails` shape as a handler, including the custom-action `data`
payload.

| Param              | Type                                                                          |
| ------------------ | ----------------------------------------------------------------------------- |
| **`eventName`**    | <code>'action'</code>                                                         |
| **`listenerFunc`** | <code>(details: <a href="#actiondetails">ActionDetails</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### setPositionState(...)

```typescript
setPositionState(options: PositionStateOptions) => Promise<void>
```

Update current media playback position, duration and speed. Analogue to
calling [setPositionState() of the MediaSession
interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/setPositionState)
when using the Media Session API directly.

On Android, omitting `duration`, `position` or `playbackRate`
**preserves** the previously set value for that field; pass `0` / `0` / `1`
explicitly to reset them.

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code><a href="#positionstateoptions">PositionStateOptions</a></code> |

--------------------


### Interfaces


#### MetadataOptions

| Prop          | Type                |
| ------------- | ------------------- |
| **`album`**   | <code>string</code> |
| **`artist`**  | <code>string</code> |
| **`artwork`** | <code>any[]</code>  |
| **`title`**   | <code>string</code> |


#### PlaybackStateOptions

| Prop                | Type                                   |
| ------------------- | -------------------------------------- |
| **`playbackState`** | <code>MediaSessionPlaybackState</code> |


#### ActionHandlerOptions

| Prop          | Type                                                                      | Description                                                                                                                                                                                                                                                                                                                                                                                  |
| ------------- | ------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`action`**  | <code>any</code>                                                          | The action to handle. In addition to the standard [MediaSessionAction](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/setActionHandler) values, any arbitrary string may be passed to register a *custom action*. Custom actions are only surfaced on Android (as an extra button in the media notification / session custom layout); on Web and iOS they are a silent no-op. |
| **`label`**   | <code>string</code>                                                       | Display label for a *custom action*. Android only. A custom action button is only rendered when a `label` is provided; standard actions ignore this field.                                                                                                                                                                                                                                   |
| **`icon`**    | <code><a href="#mediasessionactionicon">MediaSessionActionIcon</a></code> | Icon for a *custom action* button. Android only. Maps to one of Media3's built-in `CommandButton` icons; an unknown or missing value falls back to an undefined icon. Standard actions ignore this field.                                                                                                                                                                                    |
| **`iconUri`** | <code>string</code>                                                       | URI of a custom drawable for a *custom action* button (e.g. a `content://`, `android.resource://` or `file://` URI). Android only; a no-op on Web and iOS. When provided, `iconUri` takes precedence over `icon` for the button's appearance — the `icon` constant is still kept as a fallback. Standard actions ignore this field.                                                          |
| **`enabled`** | <code>boolean</code>                                                      | Whether a *custom action* button is enabled. Android only; a no-op on Web and iOS. Defaults to `true`. A disabled button is rendered greyed out and non-interactive while its layout slot is preserved. Standard actions ignore this field.                                                                                                                                                  |


#### ActionDetails

| Prop             | Type                                 | Description                                                                                                                                                                                                                                                                                                                                         |
| ---------------- | ------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`action`**     | <code>any</code>                     |                                                                                                                                                                                                                                                                                                                                                     |
| **`seekOffset`** | <code>number \| null</code>          |                                                                                                                                                                                                                                                                                                                                                     |
| **`seekTime`**   | <code>number \| null</code>          |                                                                                                                                                                                                                                                                                                                                                     |
| **`data`**       | <code>{ [key: string]: any; }</code> | Extra per-tap data for the action. On Android this carries the *custom action* arguments/extras: the `Bundle` passed by the controller for a custom command (and any `customExtras` configured on the command) is marshalled into this object (string/boolean/number values). Standard actions do not populate this field; on Web/iOS it is unused. |


#### PositionStateOptions

| Prop               | Type                |
| ------------------ | ------------------- |
| **`duration`**     | <code>number</code> |
| **`playbackRate`** | <code>number</code> |
| **`position`**     | <code>number</code> |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


### Type Aliases


#### MediaSessionActionIcon

Built-in icon for a custom action button (Android only). Each value maps to a
Media3 `CommandButton.ICON_*` constant. Any other/missing value falls back to
`CommandButton.ICON_UNDEFINED`.

<code>'play' | 'pause' | 'stop' | 'next' | 'previous' | 'fast-forward' | 'rewind' | 'skip-forward' | 'skip-back' | 'heart' | 'heart-filled' | 'star' | 'star-filled' | 'thumb-up' | 'thumb-up-filled' | 'thumb-down' | 'thumb-down-filled' | 'shuffle' | 'shuffle-on' | 'repeat' | 'bookmark' | 'bookmark-filled' | 'plus' | 'minus'</code>


#### ActionHandler

<code>(details: <a href="#actiondetails">ActionDetails</a>): void</code>

</docgen-api>
