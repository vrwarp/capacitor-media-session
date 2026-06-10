import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@jofr/capacitor-media-session', () => ({
  MediaSession: {
    setMetadata: vi.fn().mockResolvedValue(undefined),
    setPlaybackState: vi.fn().mockResolvedValue(undefined),
    setPositionState: vi.fn().mockResolvedValue(undefined),
    setActionHandler: vi.fn().mockResolvedValue(undefined),
  },
}));

import { MediaSession } from '@jofr/capacitor-media-session';
import { setupMediaSession, TRACK_METADATA } from '../src/js/media-session.js';

const ALL_ACTIONS = [
  'play',
  'pause',
  'seekto',
  'seekforward',
  'seekbackward',
  'previoustrack',
  'nexttrack',
  'stop',
];

function createAudioElement() {
  const audio = document.createElement('audio');
  let paused = true;
  let currentTime = 0;
  Object.defineProperty(audio, 'paused', {
    get: () => paused,
    configurable: true,
  });
  Object.defineProperty(audio, 'currentTime', {
    get: () => currentTime,
    set: (value) => {
      currentTime = value;
    },
    configurable: true,
  });
  Object.defineProperty(audio, 'duration', {
    get: () => 100,
    configurable: true,
  });
  Object.defineProperty(audio, 'playbackRate', {
    value: 1,
    writable: true,
    configurable: true,
  });
  audio.play = vi.fn(() => {
    paused = false;
    audio.dispatchEvent(new Event('play'));
    return Promise.resolve();
  });
  audio.pause = vi.fn(() => {
    paused = true;
    audio.dispatchEvent(new Event('pause'));
  });
  return audio;
}

function getActionHandler(action) {
  const call = MediaSession.setActionHandler.mock.calls.find(
    ([options]) => options.action === action,
  );
  expect(call, `handler for action "${action}"`).toBeDefined();
  return call[1];
}

function lastPlaybackState() {
  const calls = MediaSession.setPlaybackState.mock.calls;
  return calls[calls.length - 1][0].playbackState;
}

describe('setupMediaSession', () => {
  let audio;

  beforeEach(() => {
    vi.clearAllMocks();
    audio = createAudioElement();
    setupMediaSession(audio);
  });

  it('registers handlers for all media session actions', () => {
    const registered = MediaSession.setActionHandler.mock.calls.map(
      ([options]) => options.action,
    );
    for (const action of ALL_ACTIONS) {
      expect(registered).toContain(action);
    }
    for (const [, handler] of MediaSession.setActionHandler.mock.calls) {
      expect(handler).toBeTypeOf('function');
    }
  });

  it('reports "playing" state and metadata when playback starts', () => {
    audio.play();

    expect(lastPlaybackState()).toBe('playing');
    expect(MediaSession.setMetadata).toHaveBeenCalledWith(TRACK_METADATA);
  });

  it('reports "paused" state when playback pauses', () => {
    audio.play();
    audio.pause();

    expect(lastPlaybackState()).toBe('paused');
  });

  it('reports "none" state when playback ends', () => {
    audio.play();
    audio.dispatchEvent(new Event('ended'));

    expect(lastPlaybackState()).toBe('none');
  });

  it('updates the position state when seeking', () => {
    audio.currentTime = 42;
    audio.dispatchEvent(new Event('seeked'));

    expect(MediaSession.setPositionState).toHaveBeenLastCalledWith({
      position: 42,
      duration: 100,
      playbackRate: 1,
    });
  });

  it('updates the position state when the duration becomes known', () => {
    audio.dispatchEvent(new Event('durationchange'));

    expect(MediaSession.setPositionState).toHaveBeenCalledWith({
      position: 0,
      duration: 100,
      playbackRate: 1,
    });
  });

  it('starts playback for the "play" action', () => {
    getActionHandler('play')({ action: 'play' });

    expect(audio.play).toHaveBeenCalled();
  });

  it('pauses playback for the "pause" action', () => {
    audio.play();
    getActionHandler('pause')({ action: 'pause' });

    expect(audio.pause).toHaveBeenCalled();
  });

  it('seeks to the requested time for the "seekto" action', () => {
    getActionHandler('seekto')({ action: 'seekto', seekTime: 31.5 });

    expect(audio.currentTime).toBe(31.5);
  });

  it('seeks forward by the default offset for the "seekforward" action', () => {
    audio.currentTime = 10;
    getActionHandler('seekforward')({ action: 'seekforward' });

    expect(audio.currentTime).toBe(40);
  });

  it('seeks forward by the provided offset', () => {
    audio.currentTime = 10;
    getActionHandler('seekforward')({ action: 'seekforward', seekOffset: 5 });

    expect(audio.currentTime).toBe(15);
  });

  it('seeks backward and clamps at the start for the "seekbackward" action', () => {
    audio.currentTime = 10;
    getActionHandler('seekbackward')({ action: 'seekbackward' });

    expect(audio.currentTime).toBe(0);
  });

  it('restarts the track for the "previoustrack" and "nexttrack" actions', () => {
    audio.currentTime = 55;
    getActionHandler('previoustrack')({ action: 'previoustrack' });
    expect(audio.currentTime).toBe(0);

    audio.currentTime = 55;
    getActionHandler('nexttrack')({ action: 'nexttrack' });
    expect(audio.currentTime).toBe(0);
  });

  it('stops playback and reports "none" for the "stop" action', () => {
    audio.play();
    getActionHandler('stop')({ action: 'stop' });

    expect(audio.pause).toHaveBeenCalled();
    expect(lastPlaybackState()).toBe('none');
  });
});
