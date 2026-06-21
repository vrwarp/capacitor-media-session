import { WebPlugin } from '@capacitor/core';

import type { MetadataOptions, PlaybackStateOptions, ActionHandlerOptions, ActionHandler, PositionStateOptions, MediaSessionPlugin } from './definitions';

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
    async setMetadata(options: MetadataOptions): Promise<void> {
        if ('mediaSession' in navigator) {
            navigator.mediaSession.metadata = new MediaMetadata(options);
        } else {
            throw this.unavailable('Media Session API not available in this browser.');
        }
    }

    async setPlaybackState(options: PlaybackStateOptions): Promise<void> {
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
                navigator.mediaSession.setActionHandler(options.action as MediaSessionAction, handler);
            } catch (e) {
                throw this.unavailable(`Action "${options.action}" is not supported in this browser.`);
            }
        } else {
            throw this.unavailable('Media Session API not available in this browser.');
        }
    };

    async setPositionState(options: PositionStateOptions): Promise<void> {
        if ('mediaSession' in navigator) {
            navigator.mediaSession.setPositionState(options);
        } else {
            throw this.unavailable('Media Session API not available in this browser.');
        }
    };
}
