/**
 * Test double for the browser's Media Session API (jsdom ships neither
 * `navigator.mediaSession` nor `MediaMetadata`). Mirrors the observable
 * behaviour the plugin relies on, including the spec's throwing edge cases:
 * assigning an invalid `playbackState` and registering an unsupported action
 * both throw `TypeError` in real browsers.
 */

export interface RecordedActionDetails {
  action: string;
  seekTime?: number | null;
  seekOffset?: number | null;
  fastSeek?: boolean | null;
}

export class FakeMediaMetadata {
  title: string;
  artist: string;
  album: string;
  artwork: { src: string; sizes?: string; type?: string }[];

  constructor(init: MediaMetadataInit = {}) {
    this.title = init.title ?? '';
    this.artist = init.artist ?? '';
    this.album = init.album ?? '';
    this.artwork = (init.artwork ?? []).map(entry => ({ ...entry }));
  }
}

const PLAYBACK_STATES = ['none', 'paused', 'playing'];

export class FakeMediaSession {
  metadata: FakeMediaMetadata | null = null;
  positionState:
    | { duration?: number; playbackRate?: number; position?: number }
    | undefined = undefined;
  /** Registered (non-null) handlers, keyed by action. */
  readonly handlers = new Map<
    string,
    (details: RecordedActionDetails) => void
  >();
  /** Actions this "browser" supports; others throw on setActionHandler like Chrome/Safari do. */
  readonly supportedActions = new Set([
    'play',
    'pause',
    'seekto',
    'seekforward',
    'seekbackward',
    'nexttrack',
    'previoustrack',
    'stop',
  ]);

  private playbackStateInternal = 'none';

  get playbackState(): string {
    return this.playbackStateInternal;
  }

  set playbackState(value: string) {
    if (!PLAYBACK_STATES.includes(value)) {
      throw new TypeError(
        `Failed to set the 'playbackState' property on 'MediaSession': ` +
          `The provided value '${value}' is not a valid enum value.`,
      );
    }
    this.playbackStateInternal = value;
  }

  setActionHandler(
    action: string,
    handler: ((details: RecordedActionDetails) => void) | null,
  ): void {
    if (!this.supportedActions.has(action)) {
      throw new TypeError(
        `Failed to execute 'setActionHandler' on 'MediaSession': ` +
          `The provided value '${action}' is not a valid enum value.`,
      );
    }
    if (handler === null) {
      this.handlers.delete(action);
    } else {
      this.handlers.set(action, handler);
    }
  }

  setPositionState(state?: {
    duration?: number;
    playbackRate?: number;
    position?: number;
  }): void {
    if (state && Object.keys(state).length > 0) {
      const duration = state.duration;
      if (
        typeof duration !== 'number' ||
        Number.isNaN(duration) ||
        duration < 0
      ) {
        throw new TypeError(
          `Failed to execute 'setPositionState' on 'MediaSession': ` +
            `The provided duration cannot be less than zero.`,
        );
      }
      const position = state.position ?? 0;
      if (position < 0 || position > duration) {
        throw new TypeError(
          `Failed to execute 'setPositionState' on 'MediaSession': ` +
            `The provided position cannot exceed the duration.`,
        );
      }
      if (state.playbackRate === 0) {
        throw new TypeError(
          `Failed to execute 'setPositionState' on 'MediaSession': ` +
            `The provided playbackRate cannot be equal to zero.`,
        );
      }
    }
    this.positionState = state ? { ...state } : undefined;
  }

  /** Simulates the browser/OS invoking a registered action handler. */
  trigger(action: string, details: Partial<RecordedActionDetails> = {}): void {
    const handler = this.handlers.get(action);
    if (!handler) {
      throw new Error(`No handler registered for action "${action}"`);
    }
    handler({ action, ...details });
  }
}

/**
 * Installs the fake onto `navigator` and the `MediaMetadata` global. Returns
 * the fake for direct inspection/triggering. Call `uninstallFakeMediaSession`
 * (or rely on a fresh jsdom per test file) to remove it.
 */
export function installFakeMediaSession(): FakeMediaSession {
  const fake = new FakeMediaSession();
  Object.defineProperty(navigator, 'mediaSession', {
    value: fake,
    configurable: true,
    writable: true,
  });
  (globalThis as unknown as { MediaMetadata: unknown }).MediaMetadata =
    FakeMediaMetadata;
  return fake;
}

/** Removes the fake so `'mediaSession' in navigator` is false again. */
export function uninstallFakeMediaSession(): void {
  delete (navigator as unknown as { mediaSession?: unknown }).mediaSession;
  delete (globalThis as unknown as { MediaMetadata?: unknown }).MediaMetadata;
}
