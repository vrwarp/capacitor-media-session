package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Tests for the proxy player: mapping of the JavaScript media session state to Media3 state and
 * forwarding of Media3 player commands back to JavaScript actions.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WebViewProxyPlayerTest {
    private static final String[] ALL_ACTIONS = {
        "play", "pause", "seekto", "seekforward", "seekbackward", "nexttrack", "previoustrack", "stop"
    };

    private WebViewProxyPlayer player;
    private final List<String> receivedActions = new ArrayList<>();
    private final List<Double> receivedSeekTimes = new ArrayList<>();
    private final List<Double> receivedSeekOffsets = new ArrayList<>();

    @Before
    public void setUp() {
        player = new WebViewProxyPlayer();
        player.setActionCallback((action, seekTime, seekOffset) -> {
            receivedActions.add(action);
            receivedSeekTimes.add(seekTime);
            receivedSeekOffsets.add(seekOffset);
        });
    }

    private void updateState(String playbackState, String... supportedActions) {
        updateState(playbackState, 100.0, 10.0, 1.0, supportedActions);
    }

    private void updateState(String playbackState, double duration, double position, double playbackRate,
                             String... supportedActions) {
        player.updateSessionState(
            playbackState,
            "Test Title",
            "Test Artist",
            "Test Album",
            null,
            duration,
            position,
            playbackRate,
            new HashSet<>(Arrays.asList(supportedActions))
        );
    }

    @Test
    public void initialStateIsIdle() {
        assertEquals(Player.STATE_IDLE, player.getPlaybackState());
        assertFalse(player.getPlayWhenReady());
        assertFalse(player.isPlaying());
    }

    @Test
    public void playingStateMapsToReadyAndPlaying() {
        updateState("playing", ALL_ACTIONS);

        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertTrue(player.getPlayWhenReady());
        assertTrue(player.isPlaying());
    }

    @Test
    public void pausedStateMapsToReadyAndNotPlaying() {
        updateState("paused", ALL_ACTIONS);

        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertFalse(player.getPlayWhenReady());
        assertFalse(player.isPlaying());
    }

    @Test
    public void noneStateMapsToIdle() {
        updateState("playing", ALL_ACTIONS);
        updateState("none", ALL_ACTIONS);

        assertEquals(Player.STATE_IDLE, player.getPlaybackState());
        assertFalse(player.isPlaying());
    }

    @Test
    public void metadataIsExposedToMedia3() {
        updateState("playing", ALL_ACTIONS);

        MediaMetadata metadata = player.getMediaMetadata();
        assertEquals("Test Title", String.valueOf(metadata.title));
        assertEquals("Test Artist", String.valueOf(metadata.artist));
        assertEquals("Test Album", String.valueOf(metadata.albumTitle));
    }

    @Test
    public void artworkDataIsExposedToMedia3() {
        byte[] artwork = new byte[] { 1, 2, 3, 4 };
        player.updateSessionState("playing", "T", "A", "A", artwork, 100.0, 0.0, 1.0,
            new HashSet<>(Arrays.asList(ALL_ACTIONS)));

        assertNotNull(player.getMediaMetadata().artworkData);
        assertArrayEqualsCompat(artwork, player.getMediaMetadata().artworkData);
    }

    private static void assertArrayEqualsCompat(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual));
    }

    @Test
    public void durationAndPositionAreExposedInMilliseconds() {
        updateState("paused", 100.0, 10.5, 1.0, ALL_ACTIONS);

        assertEquals(100_000L, player.getDuration());
        assertEquals(10_500L, player.getCurrentPosition());
    }

    @Test
    public void unknownDurationIsExposedAsTimeUnset() {
        updateState("paused", 0.0, 0.0, 1.0, ALL_ACTIONS);

        assertEquals(C.TIME_UNSET, player.getDuration());
    }

    @Test
    public void positionIsClampedToDuration() {
        updateState("paused", 100.0, 200.0, 1.0, ALL_ACTIONS);

        assertEquals(100_000L, player.getCurrentPosition());
    }

    @Test
    public void playbackRateIsExposedAsPlaybackParameters() {
        updateState("playing", 100.0, 0.0, 1.5, ALL_ACTIONS);

        assertEquals(1.5f, player.getPlaybackParameters().speed, 0.0001f);
    }

    @Test
    public void invalidPlaybackRateFallsBackToNormalSpeed() {
        updateState("playing", 100.0, 0.0, 0.0, ALL_ACTIONS);

        assertEquals(1.0f, player.getPlaybackParameters().speed, 0.0001f);
    }

    @Test
    public void commandsAreUnavailableWithoutActionHandlers() {
        updateState("playing");

        assertFalse(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE));
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM));
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD));
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_BACK));
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT));
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS));
        assertFalse(player.isCommandAvailable(Player.COMMAND_STOP));
    }

    @Test
    public void commandsBecomeAvailableWithActionHandlers() {
        updateState("playing", ALL_ACTIONS);

        assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE));
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM));
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD));
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_BACK));
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT));
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM));
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS));
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM));
        assertTrue(player.isCommandAvailable(Player.COMMAND_STOP));
    }

    @Test
    public void neighboringPlaceholderItemsExistForTrackNavigation() {
        updateState("playing", ALL_ACTIONS);

        assertEquals(3, player.getMediaItemCount());
        assertEquals(1, player.getCurrentMediaItemIndex());
        assertTrue(player.hasNextMediaItem());
        assertTrue(player.hasPreviousMediaItem());
    }

    @Test
    public void noPlaceholderItemsWithoutTrackNavigationHandlers() {
        updateState("playing", "play", "pause", "seekto");

        assertEquals(1, player.getMediaItemCount());
        assertEquals(0, player.getCurrentMediaItemIndex());
        assertFalse(player.hasNextMediaItem());
        assertFalse(player.hasPreviousMediaItem());
    }

    @Test
    public void playCommandTriggersPlayAction() {
        updateState("paused", ALL_ACTIONS);

        player.play();

        assertEquals(List.of("play"), receivedActions);
        // Optimistic update: the system UI should reflect the expected state immediately.
        assertTrue(player.getPlayWhenReady());
    }

    @Test
    public void pauseCommandTriggersPauseAction() {
        updateState("playing", ALL_ACTIONS);

        player.pause();

        assertEquals(List.of("pause"), receivedActions);
        assertFalse(player.getPlayWhenReady());
    }

    @Test
    public void playCommandWithoutHandlerIsIgnored() {
        updateState("paused", "seekto");

        player.play();

        assertTrue(receivedActions.isEmpty());
        assertFalse(player.getPlayWhenReady());
    }

    @Test
    public void seekToCommandTriggersSeektoActionInSeconds() {
        updateState("playing", ALL_ACTIONS);

        player.seekTo(30_000L);

        assertEquals(List.of("seekto"), receivedActions);
        assertEquals(30.0, receivedSeekTimes.get(0), 0.0001);
        // A seekto carries an absolute position, not a relative offset.
        assertNull(receivedSeekOffsets.get(0));
        // Optimistic update so the seek bar does not snap back.
        assertEquals(30_000L, player.getCurrentPosition());
    }

    @Test
    public void seekToNextTriggersNexttrackAction() {
        updateState("playing", ALL_ACTIONS);

        player.seekToNext();

        assertEquals(List.of("nexttrack"), receivedActions);
    }

    @Test
    public void seekToNextMediaItemTriggersNexttrackAction() {
        updateState("playing", ALL_ACTIONS);

        player.seekToNextMediaItem();

        assertEquals(List.of("nexttrack"), receivedActions);
    }

    @Test
    public void seekToPreviousTriggersPrevioustrackAction() {
        updateState("playing", ALL_ACTIONS);

        player.seekToPrevious();

        assertEquals(List.of("previoustrack"), receivedActions);
    }

    @Test
    public void seekToPreviousMediaItemTriggersPrevioustrackAction() {
        updateState("playing", ALL_ACTIONS);

        player.seekToPreviousMediaItem();

        assertEquals(List.of("previoustrack"), receivedActions);
    }

    @Test
    public void seekForwardTriggersSeekforwardAction() {
        updateState("playing", ALL_ACTIONS);

        player.seekForward();

        assertEquals(List.of("seekforward"), receivedActions);
        // seekforward delivers the increment as a relative offset in seconds, no absolute time.
        assertNull(receivedSeekTimes.get(0));
        assertEquals(WebViewProxyPlayer.SEEK_FORWARD_INCREMENT_MS / 1000.0, receivedSeekOffsets.get(0), 0.0001);
    }

    @Test
    public void seekBackTriggersSeekbackwardAction() {
        updateState("playing", ALL_ACTIONS);

        player.seekBack();

        assertEquals(List.of("seekbackward"), receivedActions);
        assertNull(receivedSeekTimes.get(0));
        assertEquals(WebViewProxyPlayer.SEEK_BACK_INCREMENT_MS / 1000.0, receivedSeekOffsets.get(0), 0.0001);
    }

    @Test
    public void stopCommandTriggersStopActionWithoutChangingState() {
        updateState("playing", ALL_ACTIONS);

        player.stop();

        assertEquals(List.of("stop"), receivedActions);
        // JavaScript stays the source of truth: the state only changes once JavaScript
        // acknowledges the stop with setPlaybackState("none").
        assertTrue(player.getPlayWhenReady());
    }

    @Test
    public void stateUpdateDoesNotTriggerActions() {
        updateState("playing", ALL_ACTIONS);
        updateState("paused", ALL_ACTIONS);
        updateState("none", ALL_ACTIONS);

        assertTrue(receivedActions.isEmpty());
    }

    @Test
    public void releaseDoesNotThrow() {
        updateState("playing", ALL_ACTIONS);

        player.release();
        player.release();
    }
}
