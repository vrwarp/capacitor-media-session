package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AudioFocusControllerTest {

    /** Scriptable stand-in for AudioManager. */
    static final class FakeAudioPort implements AudioFocusController.AudioPort {
        int nextRequestResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        int requests = 0;
        int abandons = 0;
        int mode = AudioManager.MODE_NORMAL;
        boolean modeSupported = true;
        AudioFocusController.ModeListener modeListener;

        @Override
        public int requestFocus(@NonNull AudioManager.OnAudioFocusChangeListener listener, boolean pauseWhenDucked) {
            requests++;
            return nextRequestResult;
        }

        @Override
        public void abandonFocus(@NonNull AudioManager.OnAudioFocusChangeListener listener) {
            abandons++;
        }

        @Override
        public int getMode() {
            return mode;
        }

        @Override
        public boolean addModeListener(@NonNull AudioFocusController.ModeListener listener) {
            if (!modeSupported) {
                return false;
            }
            modeListener = listener;
            return true;
        }

        @Override
        public void removeModeListener(@NonNull AudioFocusController.ModeListener listener) {
            modeListener = null;
        }
    }

    static final class RecordingListener implements AudioFocusController.Listener {
        final List<String> events = new ArrayList<>();
        final List<Boolean> suppression = new ArrayList<>();

        @Override
        public void onInterruption(@NonNull String phase, @NonNull String reason, boolean shouldResume) {
            events.add(phase + ":" + reason + ":" + shouldResume);
        }

        @Override
        public void onSuppressionChanged(boolean suppressed) {
            suppression.add(suppressed);
        }
    }

    private Context context;
    private FakeAudioPort audio;
    private RecordingListener listener;
    private AudioFocusController controller;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        audio = new FakeAudioPort();
        listener = new RecordingListener();
        controller = new AudioFocusController(context, new Handler(Looper.getMainLooper()), listener, audio);
    }

    private void ownedAndPlaying() {
        controller.setPolicy(true, false);
        controller.onJsPlaybackState("playing");
    }

    private void advance(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void policyNoneNeverRequestsFocus() {
        controller.onJsPlaybackState("playing");
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertEquals(0, audio.requests);
        assertTrue(listener.events.isEmpty());
    }

    @Test
    public void playingRequestsFocusOnceAndRepeatedPlayingDoesNotReRequest() {
        ownedAndPlaying();
        controller.onJsPlaybackState("playing");
        controller.onJsPlaybackState("playing");
        assertEquals(1, audio.requests);
        assertTrue(controller.isFocusRegistered());
    }

    @Test
    public void enablingPolicyWhilePlayingRequestsImmediately() {
        controller.onJsPlaybackState("playing");
        controller.setPolicy(true, false);
        assertEquals(1, audio.requests);
    }

    @Test
    public void pausedKeepsFocusRegisteredAndNoneAbandons() {
        ownedAndPlaying();
        controller.onJsPlaybackState("paused");
        assertTrue(controller.isFocusRegistered());
        assertEquals(0, audio.abandons);
        controller.onJsPlaybackState("none");
        assertFalse(controller.isFocusRegistered());
        assertEquals(1, audio.abandons);
    }

    @Test
    public void callInterruptsAndResumesAfterGainInNormalMode() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertEquals(List.of("began:transient:true"), listener.events);
        assertTrue(controller.isSuppressed());

        // The app pauses its audio in response; the suppression (and so the FGS) is kept.
        controller.onJsPlaybackState("paused");
        assertTrue(controller.isSuppressed());

        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals("ended:transient:true", listener.events.get(1));
        assertTrue("still suppressed until the app restarts playback", controller.isSuppressed());
        assertTrue(controller.isResumePending());

        controller.onJsPlaybackState("playing");
        assertFalse(controller.isSuppressed());
        assertFalse(controller.isResumePending());
        assertEquals(List.of(true, false), listener.suppression);
    }

    @Test
    public void repeatedPlayingPushDuringInterruptionDoesNotEndIt() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        // A per-track state sync that was already in flight.
        controller.onJsPlaybackState("playing");
        assertTrue(controller.isInterrupted());
        assertTrue(controller.isSuppressed());
    }

    @Test
    public void gainDuringCallScreeningWaitsForNormalMode() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        controller.onJsPlaybackState("paused");
        audio.mode = 4; // MODE_CALL_SCREENING
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(1, listener.events.size());

        advance(AudioFocusController.MODE_POLL_MS * 3);
        assertEquals(1, listener.events.size());

        audio.mode = AudioManager.MODE_NORMAL;
        advance(AudioFocusController.MODE_POLL_MS);
        assertEquals("ended:transient:true", listener.events.get(1));
    }

    @Test
    public void callModeWithoutFocusLossInterruptsAndNormalModeEndsIt() {
        ownedAndPlaying();
        audio.modeListener.onModeChanged(AudioManager.MODE_IN_COMMUNICATION);
        assertEquals(List.of("began:call:true"), listener.events);
        controller.onJsPlaybackState("paused");
        audio.modeListener.onModeChanged(AudioManager.MODE_NORMAL);
        assertEquals("ended:call:true", listener.events.get(1));
    }

    @Test
    public void modeReturningToNormalWaitsWhileFocusIsStillLost() {
        ownedAndPlaying();
        audio.modeListener.onModeChanged(AudioManager.MODE_RINGTONE);
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        controller.onJsPlaybackState("paused");
        audio.modeListener.onModeChanged(AudioManager.MODE_NORMAL);
        assertEquals("no end until focus comes back", 1, listener.events.size());
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals("ended:call:true", listener.events.get(1));
    }

    @Test
    public void permanentLossPausesWithoutResumeAndAbandons() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        assertEquals(List.of("began:loss:false"), listener.events);
        assertFalse(controller.isFocusRegistered());
        assertFalse(controller.isSuppressed());
        assertEquals(1, audio.abandons);
    }

    @Test
    public void permanentLossDuringInterruptionCancelsTheResume() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        controller.onJsPlaybackState("paused");
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        assertEquals("began:loss:false", listener.events.get(1));
        assertFalse(controller.isInterrupted());
        assertFalse(controller.isSuppressed());
    }

    @Test
    public void duckIsIgnoredUnlessPauseWhenDucked() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertTrue(listener.events.isEmpty());

        controller.setPolicy(false, false);
        controller.setPolicy(true, true);
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals(List.of("began:transient:true"), listener.events);
    }

    @Test
    public void delayedGrantStartsInterruptedAndEndsOnGain() {
        controller.setPolicy(true, false);
        audio.nextRequestResult = AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
        controller.onJsPlaybackState("playing");
        assertEquals(List.of("began:delayed:true"), listener.events);
        controller.onJsPlaybackState("paused");
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals("ended:delayed:true", listener.events.get(1));
    }

    @Test
    public void failedRequestFailsOpenAndRetriesOnNextPlaying() {
        controller.setPolicy(true, false);
        audio.nextRequestResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        controller.onJsPlaybackState("playing");
        assertTrue(listener.events.isEmpty());
        assertFalse(controller.isFocusRegistered());
        audio.nextRequestResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        controller.onJsPlaybackState("playing");
        assertEquals(2, audio.requests);
        assertTrue(controller.isFocusRegistered());
    }

    @Test
    public void controllerPauseDuringInterruptionCancelsResume() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        controller.onControllerPause();
        assertEquals("ended:cancelled:false", listener.events.get(1));
        assertFalse(controller.isSuppressed());
        // The call ending later emits nothing more.
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(2, listener.events.size());
    }

    @Test
    public void becomingNoisyWhilePlayingPausesAndDuringInterruptionCancelsResume() {
        ownedAndPlaying();
        context.sendBroadcast(new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY).setPackage(context.getPackageName()));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("began:noisy:false"), listener.events);

        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        controller.onBecomingNoisy();
        assertEquals("ended:noisy:false", listener.events.get(2));
        assertFalse(controller.isSuppressed());
    }

    @Test
    public void longInterruptionExpiresAndReleasesSuppression() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        controller.onJsPlaybackState("paused");
        advance(AudioFocusController.INTERRUPTION_CAP_MS + 1);
        assertEquals("ended:expired:false", listener.events.get(1));
        assertFalse(controller.isSuppressed());
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(2, listener.events.size());
    }

    @Test
    public void resumeAckTimeoutReleasesSuppressionWhenTheAppDoesNotRestart() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        controller.onJsPlaybackState("paused");
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertTrue(controller.isSuppressed());
        advance(AudioFocusController.RESUME_ACK_TIMEOUT_MS + 1);
        assertFalse(controller.isSuppressed());
        assertFalse(controller.isResumePending());
    }

    @Test
    public void backToBackCallsReinterruptDuringResumePending() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        controller.onJsPlaybackState("paused");
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertEquals("began:transient:true", listener.events.get(2));
        assertTrue(controller.isInterrupted());
        assertTrue(controller.isSuppressed());
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals("ended:transient:true", listener.events.get(3));
    }

    @Test
    public void policyOffTearsEverythingDown() {
        ownedAndPlaying();
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        controller.setPolicy(false, false);
        assertFalse(controller.isSuppressed());
        assertFalse(controller.isFocusRegistered());
        assertEquals(null, audio.modeListener);
    }

    @Test
    public void lossWhilePausedByUserEmitsNothing() {
        ownedAndPlaying();
        controller.onJsPlaybackState("paused");
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertTrue(listener.events.isEmpty());
        assertFalse(controller.isSuppressed());
    }

    @Test
    public void playPressedDuringACallThatStartedWhilePausedIsDelayed() {
        ownedAndPlaying();
        controller.onJsPlaybackState("paused");
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertTrue(listener.events.isEmpty());

        audio.nextRequestResult = AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
        controller.onJsPlaybackState("playing");
        assertEquals(2, audio.requests);
        assertEquals(List.of("began:delayed:true"), listener.events);
        assertTrue(controller.isSuppressed());
    }

    @Test
    public void callModesAreRecognised() {
        assertFalse(AudioFocusController.isCallMode(AudioManager.MODE_NORMAL));
        assertTrue(AudioFocusController.isCallMode(AudioManager.MODE_RINGTONE));
        assertTrue(AudioFocusController.isCallMode(AudioManager.MODE_IN_CALL));
        assertTrue(AudioFocusController.isCallMode(AudioManager.MODE_IN_COMMUNICATION));
        assertTrue(AudioFocusController.isCallMode(4));
        assertFalse(AudioFocusController.isCallMode(-1));
    }
}
