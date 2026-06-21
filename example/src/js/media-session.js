import { MediaSession } from '@jofr/capacitor-media-session';

export const TRACK_METADATA = {
    title: 'Prelude',
    artist: 'Jan Morgenstern',
    album: 'Big Buck Bunny',
    artwork: [
        { src: './assets/imgs/logo.png', type: 'image/png', sizes: '512x512' }
    ]
};

/**
 * Wires an audio element to the Media Session plugin: keeps playback, position
 * and metadata state in sync and registers handlers for all media actions.
 */
export function setupMediaSession(audioElement) {
    let playbackStopped = true;

    const updatePositionState = () => {
        MediaSession.setPositionState({
            position: audioElement.currentTime || 0,
            duration: Number.isFinite(audioElement.duration) ? audioElement.duration : 0,
            playbackRate: audioElement.playbackRate || 1
        });
    };

    const updatePlaybackState = () => {
        const playbackState = playbackStopped ? 'none' : (audioElement.paused ? 'paused' : 'playing');
        MediaSession.setPlaybackState({ playbackState });
    };

    audioElement.addEventListener('durationchange', updatePositionState);
    audioElement.addEventListener('seeked', updatePositionState);
    audioElement.addEventListener('ratechange', updatePositionState);
    audioElement.addEventListener('play', updatePositionState);
    audioElement.addEventListener('pause', updatePositionState);

    audioElement.addEventListener('play', () => {
        playbackStopped = false;
        updatePlaybackState();
        MediaSession.setMetadata(TRACK_METADATA);
    });
    audioElement.addEventListener('pause', updatePlaybackState);
    audioElement.addEventListener('ended', () => {
        playbackStopped = true;
        updatePlaybackState();
    });

    MediaSession.setActionHandler({ action: 'play' }, () => {
        audioElement.play();
    });

    MediaSession.setActionHandler({ action: 'pause' }, () => {
        audioElement.pause();
    });

    MediaSession.setActionHandler({ action: 'seekto' }, (details) => {
        audioElement.currentTime = details.seekTime;
    });

    MediaSession.setActionHandler({ action: 'seekforward' }, (details) => {
        const seekOffset = details.seekOffset ?? 30;
        audioElement.currentTime = audioElement.currentTime + seekOffset;
    });

    MediaSession.setActionHandler({ action: 'seekbackward' }, (details) => {
        const seekOffset = details.seekOffset ?? 30;
        audioElement.currentTime = Math.max(0, audioElement.currentTime - seekOffset);
    });

    // The example only has a single track, so "previous"/"next" both restart it.
    // They are registered anyway so that the corresponding buttons show up in
    // the system media controls and can be tested.
    MediaSession.setActionHandler({ action: 'previoustrack' }, () => {
        audioElement.currentTime = 0;
    });

    MediaSession.setActionHandler({ action: 'nexttrack' }, () => {
        audioElement.currentTime = 0;
    });

    MediaSession.setActionHandler({ action: 'stop' }, () => {
        playbackStopped = true;
        audioElement.pause();
        updatePlaybackState();
    });

    // Custom action: a "like" toggle. Custom actions are a non-standard action string with a
    // `label` (and optional `icon`); on Android they render as an extra Media3 custom-layout button
    // in the media notification, and they are a silent no-op on Web/iOS. Re-registering the same
    // action from inside its own handler flips the label/icon, turning it into a toggle.
    let liked = false;
    const registerLike = () => {
        MediaSession.setActionHandler(
            {
                action: 'like',
                label: liked ? 'Unlike' : 'Like',
                icon: liked ? 'heart-filled' : 'heart'
            },
            () => {
                liked = !liked;
                registerLike();
            }
        );
    };
    registerLike();
}
