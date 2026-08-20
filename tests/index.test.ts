import { beforeEach, describe, expect, it, vi } from 'vitest';

import { MediaSession } from '../src/index';
import { MediaSessionWeb } from '../src/web';

import type { FakeMediaSession } from './helpers/fake-media-session';
import { installFakeMediaSession } from './helpers/fake-media-session';

/**
 * End-to-end tests through the public `MediaSession` export: the
 * `registerPlugin` proxy, the `index.ts` `removeHandler` translation layer and
 * the web implementation together, driven against the fake browser API.
 */
describe('MediaSession (registered plugin export)', () => {
  let fake: FakeMediaSession;

  beforeEach(async () => {
    fake = installFakeMediaSession();
    // The lazy web implementation is instantiated once per module graph and
    // caches state between tests; reset the observable bits we rely on.
    fake.handlers.clear();
    await MediaSession.setPlaybackState({ playbackState: 'none' });
  });

  it('delegates plugin methods to the web implementation', async () => {
    await MediaSession.setPlaybackState({ playbackState: 'playing' });

    expect(fake.playbackState).toBe('playing');
    expect(await MediaSession.getPlaybackState()).toEqual({
      playbackState: 'playing',
    });
  });

  it('registers and triggers action handlers through the proxy', async () => {
    const handler = vi.fn();
    await MediaSession.setActionHandler({ action: 'play' }, handler);

    fake.trigger('play');

    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler.mock.calls[0][0]).toEqual({ action: 'play' });
  });

  it('translates a null handler into removeHandler:true for the implementation', async () => {
    const spy = vi.spyOn(MediaSessionWeb.prototype, 'setActionHandler');
    try {
      await MediaSession.setActionHandler({ action: 'play' }, vi.fn());
      expect(fake.handlers.has('play')).toBe(true);

      await MediaSession.setActionHandler({ action: 'play' }, null);

      expect(fake.handlers.has('play')).toBe(false);
      const removalCall = spy.mock.calls[spy.mock.calls.length - 1];
      expect(removalCall[0]).toMatchObject({
        action: 'play',
        removeHandler: true,
      });
      expect(removalCall[1]).toBeNull();
    } finally {
      spy.mockRestore();
    }
  });

  it('does not mutate the caller-supplied options object on removal', async () => {
    const options = { action: 'play' as const };
    await MediaSession.setActionHandler(options, null);

    expect('removeHandler' in options).toBe(false);
  });

  it('does not inject removeHandler when a handler is supplied', async () => {
    const spy = vi.spyOn(MediaSessionWeb.prototype, 'setActionHandler');
    try {
      await MediaSession.setActionHandler({ action: 'pause' }, vi.fn());

      const registerCall = spy.mock.calls[spy.mock.calls.length - 1];
      expect('removeHandler' in registerCall[0]).toBe(false);
    } finally {
      spy.mockRestore();
    }
  });

  it("addListener('action') delivers events and remove() detaches the listener", async () => {
    const listener = vi.fn();
    const handle = await MediaSession.addListener('action', listener);
    await MediaSession.setActionHandler({ action: 'pause' }, vi.fn());

    fake.trigger('pause');
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({ action: 'pause' });

    await handle.remove();
    fake.trigger('pause');
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('round-trips metadata through set and get', async () => {
    await MediaSession.setMetadata({
      title: 'Song',
      artist: 'Artist',
      artwork: [{ src: 'cover.png', sizes: '512x512' }],
    });

    const metadata = await MediaSession.getMetadata();
    expect(metadata.title).toBe('Song');
    expect(metadata.artist).toBe('Artist');
    expect(metadata.artwork).toEqual([{ src: 'cover.png', sizes: '512x512' }]);
  });

  it('round-trips position state through set and get', async () => {
    await MediaSession.setPositionState({
      duration: 120,
      position: 5,
      playbackRate: 1.5,
    });

    expect(await MediaSession.getPositionState()).toEqual({
      duration: 120,
      position: 5,
      playbackRate: 1.5,
    });
  });
});
