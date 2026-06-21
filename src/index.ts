import { registerPlugin } from '@capacitor/core';

import type { ActionHandler, ActionHandlerOptions, MediaSessionPlugin } from './definitions';

const MediaSessionNative = registerPlugin<MediaSessionPlugin>('MediaSession', {
  web: () => import('./web').then(m => new m.MediaSessionWeb()),
  ios: () => import('./web').then(m => new m.MediaSessionWeb())
});

// The Capacitor native bridge drops a `null` callback argument, so the native
// (Android) implementation cannot otherwise tell that a handler should be
// removed. Translate `handler === null` into a serializable `removeHandler`
// flag while keeping the public signature unchanged. All other methods are
// delegated unchanged. The web/iOS implementation forwards `null` to
// `navigator.mediaSession` directly and ignores `removeHandler`.
const MediaSession: MediaSessionPlugin = new Proxy(MediaSessionNative, {
  get(target, prop, receiver) {
    if (prop === 'setActionHandler') {
      return (options: ActionHandlerOptions, handler: ActionHandler | null): Promise<void> => {
        const patchedOptions: ActionHandlerOptions & { removeHandler?: boolean } =
          handler === null ? { ...options, removeHandler: true } : options;
        return target.setActionHandler(patchedOptions, handler);
      };
    }
    return Reflect.get(target, prop, receiver);
  }
});

export * from './definitions';
export { MediaSession };
