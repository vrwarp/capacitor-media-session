import type { PluginListenerHandle } from '@capacitor/core';

/**
 * A single artwork image for a `MetadataOptions.artwork` entry. Mirrors the
 * [`MediaImage`](https://w3c.github.io/mediasession/#dictdef-mediaimage)
 * dictionary of the Media Session Web API. Declared locally (rather than
 * relying on the ambient `lib.dom` type) so the generated documentation renders
 * the concrete shape instead of `any`.
 */
export interface MediaImage {
    /** URL of the image. May be an `http(s)://` URL or a `data:` URI (Android also supports `data:`; `blob:` is unsupported on Android). */
    src: string;
    /** Space-separated list of `WxH` sizes the image is available in (e.g. `"96x96 512x512"`), or `"any"` for scalable artwork. Used on Android to pick the most appropriate single image to fetch. */
    sizes?: string;
    /** MIME type of the image (e.g. `"image/png"`). */
    type?: string;
}

/**
 * The eight standard actions defined by the
 * [Media Session Web API](https://w3c.github.io/mediasession/#enumdef-mediasessionaction).
 * Declared locally so the generated documentation renders the concrete union
 * instead of `any`. A `setActionHandler` / `ActionDetails.action` may also carry
 * an arbitrary string (a *custom action*, Android only).
 */
export type MediaSessionAction =
    | 'play'
    | 'pause'
    | 'seekto'
    | 'seekforward'
    | 'seekbackward'
    | 'nexttrack'
    | 'previoustrack'
    | 'stop';

/**
 * The media playback state, mirroring the
 * [Media Session Web API](https://w3c.github.io/mediasession/#enumdef-mediasessionplaybackstate).
 * Declared locally so the generated documentation renders the concrete union
 * instead of `any`.
 */
export type MediaSessionPlaybackState = 'none' | 'paused' | 'playing';

export interface MetadataOptions {
    album?: string;
    artist?: string;
    artwork?: MediaImage[];
    title?: string;
}

export interface PlaybackStateOptions {
    playbackState: MediaSessionPlaybackState;
}

export interface ActionHandlerOptions {
    /**
     * The action to handle. In addition to the standard
     * [MediaSessionAction](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/setActionHandler)
     * values, any arbitrary string may be passed to register a *custom action*.
     * Custom actions are only surfaced on Android (as an extra button in the
     * media notification / session custom layout); on Web and iOS they are a
     * silent no-op.
     */
    action: MediaSessionAction | string;
    /**
     * Display label for a *custom action*. Android only. A custom action button
     * is only rendered when a `label` is provided; standard actions ignore this
     * field.
     */
    label?: string;
    /**
     * Icon for a *custom action* button. Android only. Maps to one of Media3's
     * built-in `CommandButton` icons; an unknown or missing value falls back to
     * an undefined icon. Standard actions ignore this field.
     */
    icon?: MediaSessionActionIcon;
    /**
     * URI of a custom drawable for a *custom action* button (e.g. a
     * `content://`, `android.resource://` or `file://` URI). Android only;
     * a no-op on Web and iOS. When provided, `iconUri` takes precedence over
     * `icon` for the button's appearance — the `icon` constant is still kept as
     * a fallback. Standard actions ignore this field.
     */
    iconUri?: string;
    /**
     * Whether a *custom action* button is enabled. Android only; a no-op on Web
     * and iOS. Defaults to `true`. A disabled button is rendered greyed out and
     * non-interactive while its layout slot is preserved. Standard actions ignore
     * this field.
     */
    enabled?: boolean;
}

/**
 * Built-in icon for a custom action button (Android only). Each value maps to a
 * Media3 `CommandButton.ICON_*` constant. Any other/missing value falls back to
 * `CommandButton.ICON_UNDEFINED`.
 */
export type MediaSessionActionIcon =
    | 'play'
    | 'pause'
    | 'stop'
    | 'next'
    | 'previous'
    | 'fast-forward'
    | 'rewind'
    | 'skip-forward'
    | 'skip-back'
    | 'heart'
    | 'heart-filled'
    | 'star'
    | 'star-filled'
    | 'thumb-up'
    | 'thumb-up-filled'
    | 'thumb-down'
    | 'thumb-down-filled'
    | 'shuffle'
    | 'shuffle-on'
    | 'repeat'
    | 'bookmark'
    | 'bookmark-filled'
    | 'plus'
    | 'minus';

export type ActionHandler = (details: ActionDetails) => void;

export interface ActionDetails {
    action: MediaSessionAction | string;
    seekOffset?: number | null;
    seekTime?: number | null;
    /**
     * Extra per-tap data for the action. On Android this carries the *custom
     * action* arguments/extras: the `Bundle` passed by the controller for a
     * custom command (and any `customExtras` configured on the command) is
     * marshalled into this object (string/boolean/number values). Standard
     * actions do not populate this field; on Web/iOS it is unused.
     */
    data?: { [key: string]: string | number | boolean };
}

export interface PositionStateOptions {
    duration?: number;
    playbackRate?: number;
    position?: number;
}

/**
 * Payload of the `artworkload` event, reporting the OUTCOME of an artwork
 * update from `setMetadata` (see `addListener('artworkload', ...)`).
 */
export interface ArtworkLoadEvent {
    /**
     * Whether the cover artwork was successfully loaded. `false` means the
     * selected image failed to fetch/decode (the displayed cover is cleared) or
     * the supplied `artwork` array had no usable `src`. `true` means an image was
     * loaded and is now the displayed cover.
     */
    loaded: boolean;
    /**
     * The artwork `src` the outcome refers to. When `loaded` is `true` this is
     * the `src` that succeeded; when `false` it is the `src` that was attempted
     * (omitted entirely when the array had no usable `src` to attempt).
     */
    src?: string;
}

/**
 * Payload of the `sessionunavailable` event, reporting that the Android media
 * session could not be created (see `addListener('sessionunavailable', ...)`).
 */
export interface SessionUnavailableEvent {
    /**
     * Why the media session could not be created, as
     * `ExceptionClassName: message` — e.g.
     * `IllegalStateException: Session ID must be unique. ID=MediaSession-0`.
     * Diagnostic text, not a stable API: match on the event, not on this string.
     */
    reason: string;
}

/**
 * Options for `setAudioFocusPolicy`.
 */
export interface AudioFocusPolicyOptions {
    /**
     * `'owned'`: the plugin requests Android audio focus whenever playback
     * starts and reports interruptions (phone calls, another app taking over,
     * headphones unplugged) through the `interruption` event. `'none'` (the
     * default): no focus request, no events, behaviour unchanged.
     *
     * Only use `'owned'` when the WebView does not request focus for the same
     * audio itself. An `<audio>`/`<video>` element does (Chromium requests focus
     * for it), so the two requests would take focus from each other; Web Audio
     * and native audio such as text-to-speech do not.
     */
    mode: 'owned' | 'none';
    /**
     * Treat "may duck" focus losses (notification sounds, navigation prompts)
     * like a call and pause. Default `false`: Android ducks the app's own audio.
     */
    pauseWhenDucked?: boolean;
}

/**
 * Payload of the `interruption` event (Android, policy `'owned'` only).
 */
export interface InterruptionEvent {
    /** `'began'`: pause now. `'ended'`: the interruption is over. */
    phase: 'began' | 'ended';
    /**
     * Why: `'transient'` (temporary focus loss, e.g. a call), `'call'` (the
     * audio mode entered a call mode, Android 12+), `'delayed'` (playback was
     * started during a call), `'loss'` (another app took over playback),
     * `'noisy'` (the audio output went away, e.g. headphones unplugged),
     * `'expired'` (the interruption lasted over 10 minutes), `'cancelled'`
     * (the user paused from a media controller during the interruption).
     */
    reason: 'transient' | 'call' | 'delayed' | 'loss' | 'noisy' | 'expired' | 'cancelled';
    /**
     * On `'began'`: whether playback is expected to resume when it ends. On
     * `'ended'`: whether to resume now (only if the user did not pause or start
     * something else meanwhile — the app decides).
     */
    shouldResume: boolean;
}

export interface MediaSessionPlugin {
    /**
     * Sets metadata of the currently playing media. Analogue to setting the
     * [metadata property of the MediaSession
     * interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/metadata)
     * when using the Media Session API directly.
     *
     * Omitted fields **preserve** their previously set values on every platform
     * (partial updates merge): `setMetadata({ artist: 'X' })` keeps the earlier
     * `title` and cover artwork in the system UI.
     *
     * On Android, artwork handling differs from the Web API: only a **single**
     * `artwork` entry is fetched, selected by its `sizes` (the smallest image at
     * least ~512px, otherwise the largest available). Fetching is
     * **asynchronous** — the returned promise resolves before the image has
     * loaded, and the artwork appears shortly afterwards. Supplying an `artwork`
     * array whose selected image fails to load **clears** any previously shown
     * cover, whereas omitting the `artwork` property entirely **preserves** it.
     * The selected artwork `src` may be an `http(s)://` URL (fetched, with large
     * images downsampled) or a `data:` URI (base64 or percent-encoded); `blob:`
     * URLs are not supported on Android. Encoded artwork payloads are capped at
     * 8 MB (HTTP and `data:` alike); anything larger is treated as a failed
     * load.
     */
    setMetadata(options: MetadataOptions): Promise<void>;
    /**
     * Indicate whether media is playing or not. Analogue to setting the
     * [playbackState property of the MediaSession
     * interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/playbackState)
     * when using the Media Session API directly.
     *
     * The promise **rejects** for values other than `'none'`, `'paused'` or
     * `'playing'` on every platform (browsers throw a `TypeError`; Android
     * validates for parity).
     */
    setPlaybackState(options: PlaybackStateOptions): Promise<void>;
    /**
     * Sets handler for media session actions (e.g. initiated via onscreen media
     * controls or physical buttons). Analogue to calling [setActionHandler() of
     * the MediaSession
     * interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/setActionHandler)
     * when using the Media Session API directly.
     *
     * Passing `null` as the handler removes a previously registered handler for
     * that action.
     *
     * In addition to the standard Media Session actions, an arbitrary action
     * string may be passed to register a *custom action*. On Android a custom
     * action is published as an extra button in the media notification / session
     * custom layout; the `label` (required for the button to render) and `icon`
     * options control its appearance. Re-registering the same custom action
     * replaces the button, and passing `null` removes it. Custom actions are a
     * silent no-op on Web and iOS, where only the standard actions are
     * supported.
     *
     * **Android promise behaviour:** the returned promise does **not** settle on
     * registration — the underlying call is kept alive to deliver every tap to
     * `handler`. Do **not** `await` it on Android (it would hang). If you only
     * want to know an action fired (rather than registering a per-action
     * handler), prefer `addListener('action', ...)`, whose promise settles
     * idiomatically.
     */
    setActionHandler(options: ActionHandlerOptions, handler: ActionHandler | null): Promise<void>;
    /**
     * Returns the last metadata set via `setMetadata`. This is a read-back of
     * the plugin's own cached value (the most recently set fields), **not** a
     * live read of the underlying system media session.
     *
     * On Web the cache is enriched from `navigator.mediaSession.metadata` when
     * available. On Android the **text** fields (`title`, `artist`, `album`) are
     * returned along with the original `artwork` array returned **verbatim** from
     * the cache (the array as supplied to `setMetadata`, not a re-encoding of the
     * decoded image bytes); the `artwork` key is omitted only when no artwork has
     * ever been set.
     *
     * This returns what you **set**, not necessarily what is **displayed**: the
     * returned `artwork` array is the raw array supplied to `setMetadata`. On
     * Android, if the selected image later fails to fetch/decode the displayed
     * cover is cleared, yet `getMetadata` still returns the supplied array. For
     * the actual artwork load outcome, listen to the `artworkload` event.
     */
    getMetadata(): Promise<MetadataOptions>;
    /**
     * Returns the last playback state set via `setPlaybackState` (defaults to
     * `"none"`). This is a read-back of the plugin's own cached value, **not** a
     * live read of the underlying system media session. On Web the value falls
     * back to `navigator.mediaSession.playbackState` when no value has been set
     * yet.
     */
    getPlaybackState(): Promise<PlaybackStateOptions>;
    /**
     * Returns the last position state set via `setPositionState` (`duration`,
     * `position`, `playbackRate`). This is a read-back of the plugin's own
     * cached value, **not** a live read of the underlying system media session.
     */
    getPositionState(): Promise<PositionStateOptions>;
    /**
     * Adds a listener that fires on **every** media action (standard *and*
     * custom) the user triggers — system media controls, hardware media buttons
     * or a custom-action button. This fires **in addition to** any handler
     * registered for the same action via `setActionHandler`; both are invoked.
     *
     * Unlike `setActionHandler` (whose promise is kept alive on Android), the
     * promise returned here settles idiomatically with a `PluginListenerHandle`
     * you can `await` and later `remove()`. The listener receives the same
     * `ActionDetails` shape as a handler, including the custom-action `data`
     * payload.
     */
    addListener(
        eventName: 'action',
        listenerFunc: (details: ActionDetails) => void,
    ): Promise<PluginListenerHandle>;
    /**
     * Adds a listener that fires after a `setMetadata` artwork **outcome**,
     * reporting whether the cover artwork actually loaded.
     *
     * On Android it fires once the artwork update settles:
     * `loaded: false` means the selected image failed to fetch/decode (the
     * displayed cover is cleared) OR the supplied `artwork` array had no usable
     * `src`; `loaded: true` carries the `src` that succeeded. It does **not**
     * fire when the `artwork` key is omitted from `setMetadata` (which preserves
     * the previous cover). On Web it fires `loaded: true` (with the first
     * artwork `src`) right after the metadata is handed off to
     * `navigator.mediaSession`, and likewise only when an `artwork` array was
     * supplied.
     *
     * Use this to learn the real load outcome — `getMetadata` returns what you
     * **set**, not necessarily what is **displayed**.
     */
    addListener(
        eventName: 'artworkload',
        listenerFunc: (event: ArtworkLoadEvent) => void,
    ): Promise<PluginListenerHandle>;
    /**
     * Adds a listener that fires when the Android media session could not be
     * created, so this run has **no media notification and no lock-screen or
     * hardware-media-button controls**. Audio produced by your app keeps
     * playing; only the system-side session is missing.
     *
     * The plugin retries session creation once before reporting this, and
     * reports it instead of throwing — the service is created inside
     * `Service.onCreate`, where an exception would kill the whole app process on
     * launch. Use it to tell the user their lock-screen controls are gone for
     * this session, or to log it.
     *
     * Android only; it never fires on Web. The event is retained until a
     * listener consumes it, because the service binds at plugin-load time —
     * well before the web app can register a listener — so a listener added
     * later still receives it.
     */
    addListener(
        eventName: 'sessionunavailable',
        listenerFunc: (event: SessionUnavailableEvent) => void,
    ): Promise<PluginListenerHandle>;
    /**
     * Listen for audio interruptions (Android, with `setAudioFocusPolicy({ mode: 'owned' })`).
     *
     * On `'began'` with `shouldResume: true` (a phone call), pause your audio
     * and keep reporting `'paused'`: the plugin keeps the media notification and
     * foreground service alive through the call. On `'ended'` with
     * `shouldResume: true`, restart playback and report `'playing'`. The plugin
     * waits until the phone is back in normal audio mode before sending it.
     *
     * Never fires on Web.
     */
    addListener(
        eventName: 'interruption',
        listenerFunc: (event: InterruptionEvent) => void,
    ): Promise<PluginListenerHandle>;
    /**
     * Opt in to native Android audio focus, so phone calls and other apps can
     * pause and resume your playback (see `addListener('interruption', ...)`).
     * No-op on Web and iOS.
     */
    setAudioFocusPolicy(options: AudioFocusPolicyOptions): Promise<void>;
    /**
     * Update current media playback position, duration and speed. Analogue to
     * calling [setPositionState() of the MediaSession
     * interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/setPositionState)
     * when using the Media Session API directly.
     *
     * Omitting `duration`, `position` or `playbackRate` **preserves** the
     * previously set value for that field on every platform (so a position-only
     * update per tick is fine); pass `0` / `0` / `1` explicitly to reset them.
     *
     * Non-finite values (e.g. `Infinity` for live media) cannot cross the
     * native JSON bridge: on Android they behave like omitted fields, and the
     * native layer additionally guards its timeline against non-finite input
     * (a never-set duration renders as an indeterminate timeline). On Web,
     * `Infinity` is handed to the browser unchanged.
     */
    setPositionState(options: PositionStateOptions): Promise<void>;
}
