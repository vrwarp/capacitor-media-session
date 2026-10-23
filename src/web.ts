import { WebPlugin } from '@capacitor/core';

import type { MetadataOptions, PlaybackStateOptions, ActionHandlerOptions, ActionHandler, ActionDetails, PositionStateOptions, MediaSessionPlugin, MediaSessionPlaybackState, MediaSessionAction, AudioFocusPolicyOptions } from './definitions';

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

/**
 * Shallow merge that ignores keys whose value is `undefined`. A plain spread
 * would let an explicit `{ artwork: undefined }` erase the cached value, whereas
 * the native bridge drops `undefined` (so the field is "omitted" and preserved).
 */
function mergeDefined<T extends MetadataOptions | PositionStateOptions>(base: T, update: T): T {
    const result: T = { ...base };
    for (const key of Object.keys(update) as (keyof T)[]) {
        if (update[key] !== undefined) {
            result[key] = update[key];
        }
    }
    return result;
}

export class MediaSessionWeb extends WebPlugin implements MediaSessionPlugin {
    /**
     * Cached last-set values so the getters can read back what was set (the Web
     * Media Session API exposes no readable position state and only a partial
     * metadata read). Metadata and position are MERGED on set so omitted fields
     * are preserved (mirroring the Android setters); playback state is assigned.
     * The merged metadata/position caches are also what is handed to
     * `navigator.mediaSession`, so the preserve-on-omit semantics hold for the
     * browser-displayed state too, not just for the read-back getters.
     */
    private metadataCache: MetadataOptions = {};
    private playbackStateCache: MediaSessionPlaybackState = 'none';
    private positionStateCache: PositionStateOptions = {};

    async setMetadata(options: MetadataOptions): Promise<void> {
        if (!('mediaSession' in navigator)) {
            throw this.unavailable('Media Session API not available in this browser.');
        }
        // Hand the MERGED metadata (not just the per-call fields) to the browser so
        // omitted fields preserve their previous values, matching the Android
        // setter semantics documented on `setMetadata`. A bare `new
        // MediaMetadata(options)` would reset every omitted field to ''/[] here
        // while Android keeps them. The cache is only committed after the browser
        // accepted the value: `MediaMetadata` throws a TypeError for e.g. an
        // unparsable artwork `src`, and caching it first would poison every later
        // merge (and the read-back getter) with the rejected value.
        const merged = mergeDefined(this.metadataCache, options);
        navigator.mediaSession.metadata = new MediaMetadata(merged);
        this.metadataCache = merged;
        // Mirror the Android 'artworkload' outcome event: on Web the metadata
        // (incl. artwork) is handed off to navigator.mediaSession synchronously,
        // so report loaded:true with the first artwork src. Only fire when the
        // caller actually supplied an artwork array (omitting it preserves the
        // previous cover and emits nothing, matching Android).
        if (options.artwork !== undefined) {
            const src = options.artwork.length > 0 ? options.artwork[0].src : undefined;
            this.notifyListeners('artworkload', { loaded: true, src });
        }
    }

    async setPlaybackState(options: PlaybackStateOptions): Promise<void> {
        if (!('mediaSession' in navigator)) {
            throw this.unavailable('Media Session API not available in this browser.');
        }
        navigator.mediaSession.playbackState = options.playbackState;
        // Cache only after the browser accepted the value, so a rejected state is
        // never reported back by `getPlaybackState`.
        this.playbackStateCache = options.playbackState;
    }

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
                              const mapped = this.toActionDetails(options.action, details);
                              // try/finally so a throwing user handler cannot swallow the
                              // 'action' event — Android likewise emits it unconditionally.
                              try {
                                  handler(mapped);
                              } finally {
                                  this.notifyListeners('action', mapped);
                              }
                          };
                navigator.mediaSession.setActionHandler(options.action as MediaSessionAction, wrapped);
            } catch (e) {
                throw this.unavailable(`Action "${options.action}" is not supported in this browser.`);
            }
        } else {
            throw this.unavailable('Media Session API not available in this browser.');
        }
    }

    async setPositionState(options: PositionStateOptions): Promise<void> {
        if (!('mediaSession' in navigator)) {
            throw this.unavailable('Media Session API not available in this browser.');
        }
        // Pass the MERGED state so omitted fields preserve their previous values
        // (the documented Android semantics). Passing the raw per-call options
        // would make a position-only update throw a TypeError in browsers that
        // require `duration` to be present alongside `position`. Commit to the
        // cache only after the browser accepted it (it throws a TypeError for e.g.
        // `position > duration`), so a rejected update cannot poison later merges.
        const merged = mergeDefined(this.positionStateCache, options);
        navigator.mediaSession.setPositionState(merged);
        this.positionStateCache = merged;
    }

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

    /** Android only: browsers manage audio focus themselves. */
    async setAudioFocusPolicy(_options: AudioFocusPolicyOptions): Promise<void> {
        return;
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
