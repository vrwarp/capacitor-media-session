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
     */
    setActionHandler(options: ActionHandlerOptions, handler: ActionHandler | null): Promise<void>;
    /**
     * Update current media playback position, duration and speed. Analogue to
     * calling [setPositionState() of the MediaSession
     * interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/setPositionState)
     * when using the Media Session API directly.
     */
    setPositionState(options: PositionStateOptions): Promise<void>;
}
