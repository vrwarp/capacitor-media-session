import { WebPlugin } from '@capacitor/core';

import type { MetadataOptions, PlaybackStateOptions, ActionHandlerOptions, PositionStateOptions, MediaSessionPlugin, ActionHandler } from './definitions';
import type { PluginListenerHandle } from '@capacitor/core';

export class MediaSessionWeb extends WebPlugin implements MediaSessionPlugin {
    async setMetadata(options: MetadataOptions): Promise<void> {
        if ('mediaSession' in navigator) {
            navigator.mediaSession.metadata = new MediaMetadata(options as any);
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

    async setActionHandler(options: ActionHandlerOptions): Promise<void> {
        if ('mediaSession' in navigator) {
            navigator.mediaSession.setActionHandler(options.action, (details) => {
                this.notifyListeners('onMediaAction', details);
            });
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

    addListener(
        eventName: 'onMediaAction',
        listenerFunc: ActionHandler
    ): Promise<PluginListenerHandle> {
        return super.addListener(eventName, listenerFunc);
    }
}
