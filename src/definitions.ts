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
    action: MediaSessionAction
}

export type ActionHandler = (details: ActionDetails) => void;

export interface ActionDetails {
    action: MediaSessionAction;
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
    /** Registers an intent to handle a specific action. */
    setActionHandler(options: ActionHandlerOptions): Promise<void>;
    /**
     * Update current media playback position, duration and speed. Analogue to
     * calling [setPositionState() of the MediaSession
     * interface](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession/setPositionState)
     * when using the Media Session API directly.
     */
    setPositionState(options: PositionStateOptions): Promise<void>;
    /** Listen for media actions from the OS */
    addListener(
        eventName: 'onMediaAction',
        listenerFunc: ActionHandler
    ): Promise<PluginListenerHandle>;
}