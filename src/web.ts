import { WebPlugin } from '@capacitor/core';

import type { MetadataOptions, PlaybackStateOptions, ActionHandlerOptions, ActionHandler, ActionDetails, PositionStateOptions, MediaSessionPlugin, MediaSessionPlaybackState, MediaSessionAction } from './definitions';

/**
 * The eight actions defined by the Media Session Web API. Any other action
 * string is treated as a custom action, which the Web Media Session API does
 * not support, so it degrades to a silent no-op here.
 */
const STANDARD_ACTIONS: ReadonlySet<string> = new Set([
    'play',
    'pause',
    'seekto',
    'seekforward',
    'seekbackward',
    'nexttrack',
    'previoustrack',
    'stop',
]);

export class MediaSessionWeb extends WebPlugin implements MediaSessionPlugin {
    /**
     * Cached last-set values so the getters can read back what was set (the Web
     * Media Session API exposes no readable position state and only a partial
     * metadata read). Metadata and position are MERGED on set so omitted fields
     * are preserved (mirroring the Android setters); playback state is assigned.
     */
    private metadataCache: MetadataOptions = {};
    private playbackStateCache: MediaSessionPlaybackState = 'none';
    private positionStateCache: PositionStateOptions = {};

    async setMetadata(options: MetadataOptions): Promise<void> {
        this.metadataCache = { ...this.metadataCache, ...options };
        if ('mediaSession' in navigator) {
            navigator.mediaSession.metadata = new MediaMetadata(options);
            // Mirror the Android 'artworkload' outcome event: on Web the metadata
            // (incl. artwork) is handed off to navigator.mediaSession synchronously,
            // so report loaded:true with the first artwork src. Only fire when the
            // caller actually supplied an artwork array (omitting it preserves the
            // previous cover and emits nothing, matching Android).
            if ('artwork' in options) {
                const src =
                    options.artwork && options.artwork.length > 0 ? options.artwork[0].src : undefined;
                this.notifyListeners('artworkload', { loaded: true, src });
            }
        } else {
            throw this.unavailable('Media Session API not available in this browser.');
        }
    }

    async setPlaybackState(options: PlaybackStateOptions): Promise<void> {
        this.playbackStateCache = options.playbackState;
        if ('mediaSession' in navigator) {
            navigator.mediaSession.playbackState = options.playbackState;
        } else {
            throw this.unavailable('Media Session API not available in this browser.');
        }
    };

    async setActionHandler(options: ActionHandlerOptions, handler: ActionHandler | null): Promise<void> {
        if (!STANDARD_ACTIONS.has(options.action)) {
            // Custom actions are an Android-only feature; the Web Media Session
            // API only knows the standard actions, so resolve as a no-op.
            return;
        }
        if ('mediaSession' in navigator) {
            try {
                // Wrap the user handler so the registered navigator.mediaSession
                // handler ALSO emits an 'action' event — addListener('action')
                // then receives every action, in addition to the user handler
                // (which still fires first, unchanged). Removal (null) is passed
                // straight through.
                const wrapped: MediaSessionActionHandler | null =
                    handler === null
                        ? null
                        : (details: MediaSessionActionDetails) => {
                              handler(this.toActionDetails(options.action, details));
                              this.notifyListeners('action', this.toActionDetails(options.action, details));
                          };
                navigator.mediaSession.setActionHandler(options.action as MediaSessionAction, wrapped);
            } catch (e) {
                throw this.unavailable(`Action "${options.action}" is not supported in this browser.`);
            }
        } else {
            throw this.unavailable('Media Session API not available in this browser.');
        }
    };

    async setPositionState(options: PositionStateOptions): Promise<void> {
        this.positionStateCache = { ...this.positionStateCache, ...options };
        if ('mediaSession' in navigator) {
            navigator.mediaSession.setPositionState(options);
        } else {
            throw this.unavailable('Media Session API not available in this browser.');
        }
    };

    async getMetadata(): Promise<MetadataOptions> {
        // Start from our own cache, then enrich from navigator.mediaSession's
        // live metadata where present (it exposes a readable MediaMetadata).
        const result: MetadataOptions = { ...this.metadataCache };
        if ('mediaSession' in navigator && navigator.mediaSession.metadata) {
            const m = navigator.mediaSession.metadata;
            if (m.title) result.title = m.title;
            if (m.artist) result.artist = m.artist;
            if (m.album) result.album = m.album;
            if (m.artwork && m.artwork.length > 0) {
                result.artwork = m.artwork.map((a) => ({ ...a }));
            }
        }
        return result;
    }

    async getPlaybackState(): Promise<PlaybackStateOptions> {
        let playbackState = this.playbackStateCache;
        if (playbackState === 'none' && 'mediaSession' in navigator) {
            playbackState = navigator.mediaSession.playbackState;
        }
        return { playbackState };
    }

    async getPositionState(): Promise<PositionStateOptions> {
        return { ...this.positionStateCache };
    }

    /** Maps navigator's MediaSessionActionDetails to our ActionDetails shape. */
    private toActionDetails(action: string, details: MediaSessionActionDetails): ActionDetails {
        const result: ActionDetails = { action };
        if (typeof details.seekOffset === 'number') {
            result.seekOffset = details.seekOffset;
        }
        if (typeof details.seekTime === 'number') {
            result.seekTime = details.seekTime;
        }
        return result;
    }
}
