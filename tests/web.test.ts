import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ActionDetails } from '../src/definitions';
import { MediaSessionWeb } from '../src/web';

import type { FakeMediaSession } from './helpers/fake-media-session';
import {
  installFakeMediaSession,
  uninstallFakeMediaSession,
} from './helpers/fake-media-session';

describe('MediaSessionWeb', () => {
  let fake: FakeMediaSession;
  let plugin: MediaSessionWeb;

  beforeEach(() => {
    fake = installFakeMediaSession();
    plugin = new MediaSessionWeb();
  });

  describe('setMetadata', () => {
    it('hands title/artist/album/artwork to navigator.mediaSession', async () => {
      await plugin.setMetadata({
        title: 'Song',
        artist: 'Artist',
        album: 'Album',
        artwork: [{ src: 'cover.png', sizes: '512x512', type: 'image/png' }],
      });

      expect(fake.metadata).not.toBeNull();
      expect(fake.metadata?.title).toBe('Song');
      expect(fake.metadata?.artist).toBe('Artist');
      expect(fake.metadata?.album).toBe('Album');
      expect(fake.metadata?.artwork).toEqual([
        { src: 'cover.png', sizes: '512x512', type: 'image/png' },
      ]);
    });

    it('preserves omitted fields in the browser metadata (merge semantics)', async () => {
      await plugin.setMetadata({
        title: 'Song',
        artwork: [{ src: 'cover.png' }],
      });
      await plugin.setMetadata({ artist: 'Artist' });

      // The second call omitted title and artwork; both must survive in what
      // the browser displays, matching the Android setter semantics.
      expect(fake.metadata?.title).toBe('Song');
      expect(fake.metadata?.artist).toBe('Artist');
      expect(fake.metadata?.artwork).toEqual([{ src: 'cover.png' }]);
    });

    it('replaces artwork when a new artwork array is supplied', async () => {
      await plugin.setMetadata({ artwork: [{ src: 'a.png' }] });
      await plugin.setMetadata({ artwork: [{ src: 'b.png' }] });

      expect(fake.metadata?.artwork).toEqual([{ src: 'b.png' }]);
    });

    it('emits artworkload {loaded:true, src} when artwork is supplied', async () => {
      const listener = vi.fn();
      await plugin.addListener('artworkload', listener);

      await plugin.setMetadata({
        artwork: [{ src: 'cover.png' }, { src: 'other.png' }],
      });

      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener).toHaveBeenCalledWith({ loaded: true, src: 'cover.png' });
    });

    it('emits artworkload without src for an empty artwork array', async () => {
      const listener = vi.fn();
      await plugin.addListener('artworkload', listener);

      await plugin.setMetadata({ artwork: [] });

      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener.mock.calls[0][0].loaded).toBe(true);
      expect(listener.mock.calls[0][0].src).toBeUndefined();
    });

    it('does not emit artworkload when the artwork key is omitted', async () => {
      const listener = vi.fn();
      await plugin.addListener('artworkload', listener);

      await plugin.setMetadata({ title: 'Song' });

      expect(listener).not.toHaveBeenCalled();
    });

    it('rejects with an unavailable error when the Media Session API is missing', async () => {
      uninstallFakeMediaSession();

      await expect(plugin.setMetadata({ title: 'Song' })).rejects.toThrow(
        'Media Session API not available in this browser.',
      );
    });
  });

  describe('setPlaybackState / getPlaybackState', () => {
    it('assigns the playback state and reads it back', async () => {
      await plugin.setPlaybackState({ playbackState: 'playing' });

      expect(fake.playbackState).toBe('playing');
      expect(await plugin.getPlaybackState()).toEqual({
        playbackState: 'playing',
      });
    });

    it('rejects invalid playback states (browser TypeError propagates)', async () => {
      await expect(
        plugin.setPlaybackState({ playbackState: 'bogus' as never }),
      ).rejects.toThrow(TypeError);
    });

    it('falls back to navigator.mediaSession.playbackState when nothing was set', async () => {
      fake.playbackState = 'paused';

      expect(await plugin.getPlaybackState()).toEqual({
        playbackState: 'paused',
      });
    });

    it('rejects with an unavailable error when the Media Session API is missing', async () => {
      uninstallFakeMediaSession();

      await expect(
        plugin.setPlaybackState({ playbackState: 'playing' }),
      ).rejects.toThrow('Media Session API not available in this browser.');
    });
  });

  describe('setActionHandler', () => {
    it('registers a handler that receives mapped ActionDetails', async () => {
      const handler = vi.fn();
      await plugin.setActionHandler({ action: 'seekto' }, handler);

      fake.trigger('seekto', { seekTime: 42.5 });

      expect(handler).toHaveBeenCalledTimes(1);
      const details: ActionDetails = handler.mock.calls[0][0];
      expect(details.action).toBe('seekto');
      expect(details.seekTime).toBe(42.5);
      expect('seekOffset' in details).toBe(false);
    });

    it('maps seekOffset for seekbackward and strips absent keys', async () => {
      const handler = vi.fn();
      await plugin.setActionHandler({ action: 'seekbackward' }, handler);

      fake.trigger('seekbackward', { seekOffset: 10 });

      const details: ActionDetails = handler.mock.calls[0][0];
      expect(details).toEqual({ action: 'seekbackward', seekOffset: 10 });
    });

    it('delivers plain actions with only the action key', async () => {
      const handler = vi.fn();
      await plugin.setActionHandler({ action: 'play' }, handler);

      fake.trigger('play');

      expect(handler.mock.calls[0][0]).toEqual({ action: 'play' });
    });

    it("also emits the 'action' event to listeners on every trigger", async () => {
      const handler = vi.fn();
      const listener = vi.fn();
      await plugin.addListener('action', listener);
      await plugin.setActionHandler({ action: 'pause' }, handler);

      fake.trigger('pause');

      expect(handler).toHaveBeenCalledTimes(1);
      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener).toHaveBeenCalledWith({ action: 'pause' });
    });

    it("still emits the 'action' event when the user handler throws", async () => {
      const listener = vi.fn();
      await plugin.addListener('action', listener);
      await plugin.setActionHandler({ action: 'play' }, () => {
        throw new Error('handler exploded');
      });

      expect(() => fake.trigger('play')).toThrow('handler exploded');
      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener).toHaveBeenCalledWith({ action: 'play' });
    });

    it('passing null removes a registered handler', async () => {
      await plugin.setActionHandler({ action: 'play' }, vi.fn());
      expect(fake.handlers.has('play')).toBe(true);

      await plugin.setActionHandler({ action: 'play' }, null);

      expect(fake.handlers.has('play')).toBe(false);
    });

    it('treats custom actions as a silent no-op', async () => {
      await expect(
        plugin.setActionHandler({ action: 'like' }, vi.fn()),
      ).resolves.toBeUndefined();
      expect(fake.handlers.size).toBe(0);

      // Removal of a custom action is likewise a no-op.
      await expect(
        plugin.setActionHandler({ action: 'like' }, null),
      ).resolves.toBeUndefined();
    });

    it('rejects with an unavailable error for browser-unsupported standard actions', async () => {
      fake.supportedActions.delete('seekforward');

      await expect(
        plugin.setActionHandler({ action: 'seekforward' }, vi.fn()),
      ).rejects.toThrow(
        'Action "seekforward" is not supported in this browser.',
      );
    });

    it('rejects with an unavailable error when the Media Session API is missing', async () => {
      uninstallFakeMediaSession();

      await expect(
        plugin.setActionHandler({ action: 'play' }, vi.fn()),
      ).rejects.toThrow('Media Session API not available in this browser.');
    });
  });

  describe('setPositionState / getPositionState', () => {
    it('passes the position state to navigator.mediaSession', async () => {
      await plugin.setPositionState({
        duration: 300,
        position: 10,
        playbackRate: 1,
      });

      expect(fake.positionState).toEqual({
        duration: 300,
        position: 10,
        playbackRate: 1,
      });
    });

    it('merges omitted fields from the previous state (position-only update works)', async () => {
      await plugin.setPositionState({
        duration: 300,
        position: 0,
        playbackRate: 1,
      });

      // A position-only update must not throw even though the browser requires
      // duration to be present — the merged cache supplies it.
      await expect(
        plugin.setPositionState({ position: 25 }),
      ).resolves.toBeUndefined();

      expect(fake.positionState).toEqual({
        duration: 300,
        position: 25,
        playbackRate: 1,
      });
      expect(await plugin.getPositionState()).toEqual({
        duration: 300,
        position: 25,
        playbackRate: 1,
      });
    });

    it('returns an empty object before any position state was set', async () => {
      expect(await plugin.getPositionState()).toEqual({});
    });

    it('rejects with an unavailable error when the Media Session API is missing', async () => {
      uninstallFakeMediaSession();

      await expect(plugin.setPositionState({ duration: 10 })).rejects.toThrow(
        'Media Session API not available in this browser.',
      );
    });
  });

  describe('setAudioFocusPolicy', () => {
    it('is a resolved no-op on web (browsers own audio focus)', async () => {
      await expect(plugin.setAudioFocusPolicy({ mode: 'owned' })).resolves.toBeUndefined();
      await expect(plugin.setAudioFocusPolicy({ mode: 'none', pauseWhenDucked: true })).resolves.toBeUndefined();
    });
  });

  describe('getMetadata', () => {
    it('returns what was set, enriched from the live navigator metadata', async () => {
      await plugin.setMetadata({ title: 'Cached', artist: 'Cached Artist' });

      // Simulate out-of-band metadata (e.g. another script or the browser).
      fake.metadata = new (globalThis as any).MediaMetadata({
        title: 'Live',
        album: 'Live Album',
        artwork: [{ src: 'live.png' }],
      });

      const metadata = await plugin.getMetadata();
      expect(metadata.title).toBe('Live');
      expect(metadata.artist).toBe('Cached Artist');
      expect(metadata.album).toBe('Live Album');
      expect(metadata.artwork).toEqual([{ src: 'live.png' }]);
    });

    it('copies artwork entries rather than aliasing the live array', async () => {
      fake.metadata = new (globalThis as any).MediaMetadata({
        artwork: [{ src: 'live.png' }],
      });

      const metadata = await plugin.getMetadata();
      expect(metadata.artwork?.[0]).not.toBe(fake.metadata?.artwork[0]);
    });

    it('returns the cache when the Media Session API is missing', async () => {
      await plugin.setMetadata({ title: 'Cached' }).catch(() => undefined);
      uninstallFakeMediaSession();

      const metadata = await plugin.getMetadata();
      expect(metadata.title).toBe('Cached');
    });
  });
});
