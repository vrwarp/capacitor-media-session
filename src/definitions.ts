import type { PluginListenerHandle } from '@capacitor/core';

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
    data?: { [key: string]: any };
}

export interface PositionStateOptions {
    duration?: number;
    playbackRate?: number;
    position?: number;
}

export interface MediaSessionPlugin {
    /**
     * Sets metadata of the currently playing media. Analogue to setting the
     * [metadata property of the MediaSession
     * interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/metadata)
     * when using the Media Session API directly.
     *
     * On Android, artwork handling differs from the Web API: only a **single**
     * `artwork` entry is fetched, selected by its `sizes` (the smallest image at
     * least ~512px, otherwise the largest available). Fetching is
     * **asynchronous** — the returned promise resolves before the image has
     * loaded, and the artwork appears shortly afterwards. Supplying an `artwork`
     * array whose selected image fails to load **clears** any previously shown
     * cover, whereas omitting the `artwork` property entirely **preserves** it.
     */
    setMetadata(options: MetadataOptions): Promise<void>;
    /**
     * Indicate whether media is playing or not. Analogue to setting the
     * [playbackState property of the MediaSession
     * interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/playbackState)
     * when using the Media Session API directly.
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
     * available. On Android only the **text** fields (`title`, `artist`,
     * `album`) are returned; `artwork` is omitted because only the decoded image
     * bytes are cached natively, not the original `artwork` array.
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
     * Update current media playback position, duration and speed. Analogue to
     * calling [setPositionState() of the MediaSession
     * interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/setPositionState)
     * when using the Media Session API directly.
     *
     * On Android, omitting `duration`, `position` or `playbackRate`
     * **preserves** the previously set value for that field; pass `0` / `0` / `1`
     * explicitly to reset them.
     */
    setPositionState(options: PositionStateOptions): Promise<void>;
}
