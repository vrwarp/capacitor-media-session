package io.github.jofr.capacitor.mediasessionplugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;

/**
 * Opt-in Android audio focus for apps whose audio the WebView does not request focus for itself
 * (e.g. native text-to-speech, or Web Audio, which Chromium never registers for focus). Without
 * a focus request, Android has no way to tell the app that a phone call started or ended.
 *
 * <p>While the policy is {@code owned}:
 * <ul>
 *   <li>a JS {@code 'playing'} push requests {@code AUDIOFOCUS_GAIN} (usage MEDIA, content type
 *       SPEECH). The request stays registered while paused so the post-call GAIN still arrives;
 *       it is abandoned on {@code 'none'}, on a permanent loss, or when the policy is turned off.</li>
 *   <li>a transient loss (a ringing/answered cellular or Telecom VoIP call), or the audio mode
 *       entering a call mode (API 31+), emits {@code interruption {phase:'began', shouldResume:true}}
 *       and suppresses the session: the proxy player keeps reporting playWhenReady with a
 *       transient-focus-loss suppression reason, so Media3 keeps the foreground service through a
 *       call of any length.</li>
 *   <li>the end of the interruption (focus regained, gated on the audio mode being back to
 *       {@code MODE_NORMAL}, which also skips Pixel call screening) emits
 *       {@code {phase:'ended', shouldResume:true}}. The suppression is kept until JS pushes a
 *       paused-to-playing edge (its resume), or for {@link #RESUME_ACK_TIMEOUT_MS}, so the
 *       service is still in the foreground when playback restarts from the background.</li>
 *   <li>a permanent loss (another app took over playback) emits {@code began} with
 *       {@code shouldResume:false}; headphones unplugged emits reason {@code 'noisy'}.</li>
 * </ul>
 * Whether to actually pause and resume is left to JS, which knows about user actions.
 *
 * <p>Confined to the main looper: every method must be called there, and all listeners are
 * delivered there.
 */
final class AudioFocusController {
    private static final String TAG = "AudioFocusController";

    /** How long an interruption may hold the session before it gives up (no auto-resume after). */
    static final long INTERRUPTION_CAP_MS = 10 * 60 * 1000L;
    /** How long the session stays suppressed after 'ended' while JS restarts playback. */
    static final long RESUME_ACK_TIMEOUT_MS = 10_000L;
    /** Poll interval while waiting for the audio mode to return to normal after focus came back. */
    static final long MODE_POLL_MS = 1_000L;

    static final String REASON_TRANSIENT = "transient";
    static final String REASON_CALL = "call";
    static final String REASON_DELAYED = "delayed";
    static final String REASON_LOSS = "loss";
    static final String REASON_NOISY = "noisy";
    static final String REASON_EXPIRED = "expired";
    static final String REASON_CANCELLED = "cancelled";

    /** Receives interruption events and suppression changes. Called on the main looper. */
    interface Listener {
        void onInterruption(@NonNull String phase, @NonNull String reason, boolean shouldResume);

        void onSuppressionChanged(boolean suppressed);
    }

    /** Thin seam over {@link AudioManager} so tests can drive focus and mode changes. */
    interface AudioPort {
        /** Returns one of the {@code AUDIOFOCUS_REQUEST_*} constants. */
        int requestFocus(@NonNull AudioManager.OnAudioFocusChangeListener listener, boolean pauseWhenDucked);

        void abandonFocus(@NonNull AudioManager.OnAudioFocusChangeListener listener);

        int getMode();

        /** Starts delivering audio-mode changes; returns false when unsupported (API < 31). */
        boolean addModeListener(@NonNull ModeListener listener);

        void removeModeListener(@NonNull ModeListener listener);
    }

    interface ModeListener {
        void onModeChanged(int mode);
    }

    private final Context context;
    private final Handler mainHandler;
    private final Listener listener;
    private final AudioPort audio;

    private boolean owned = false;
    private boolean pauseWhenDucked = false;

    /** Last playback state pushed by JS ('none' | 'paused' | 'playing'). */
    private String jsState = "none";

    /** Whether our focus request is registered with the system (granted or delayed). */
    private boolean focusRegistered = false;
    /** Whether the system currently has our focus temporarily taken away. */
    private boolean focusLostTransient = false;

    /** An interruption is in progress and playback should resume when it ends. */
    private boolean interrupted = false;
    private String interruptionReason = REASON_TRANSIENT;
    /** 'ended' was emitted; waiting for JS to restart playback (suppression kept). */
    private boolean resumePending = false;

    private boolean suppressed = false;
    private boolean noisyRegistered = false;
    private boolean modeListening = false;

    private final Runnable interruptionCap = this::onInterruptionCap;
    private final Runnable resumeAckTimeout = this::onResumeAckTimeout;
    private final Runnable modePoll = this::checkModeAndFinish;

    private final AudioManager.OnAudioFocusChangeListener focusListener = this::onAudioFocusChange;
    private final ModeListener modeListener = this::onModeChanged;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                onBecomingNoisy();
            }
        }
    };

    AudioFocusController(@NonNull Context context, @NonNull Handler mainHandler, @NonNull Listener listener) {
        this(context, mainHandler, listener, new SystemAudioPort(context, mainHandler));
    }

    @VisibleForTesting
    AudioFocusController(@NonNull Context context, @NonNull Handler mainHandler, @NonNull Listener listener,
                         @NonNull AudioPort audio) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.mainHandler = mainHandler;
        this.listener = listener;
        this.audio = audio;
    }

    /** Turns focus ownership on ({@code owned}) or off. */
    void setPolicy(boolean owned, boolean pauseWhenDucked) {
        this.pauseWhenDucked = pauseWhenDucked;
        if (this.owned == owned) {
            return;
        }
        this.owned = owned;
        Log.i(TAG, "setPolicy owned=" + owned + " pauseWhenDucked=" + pauseWhenDucked);
        if (!owned) {
            teardown();
        } else if ("playing".equals(jsState)) {
            requestFocus();
            updateRegistrations();
        }
    }

    boolean isOwned() {
        return owned;
    }

    boolean isSuppressed() {
        return suppressed;
    }

    /** A playback state pushed by JS. */
    void onJsPlaybackState(@NonNull String state) {
        final String previous = jsState;
        jsState = state;
        if (!owned) {
            return;
        }
        switch (state) {
            case "playing":
                if (!"playing".equals(previous)) {
                    // A paused/none -> playing edge is the app (re)starting playback: it ends any
                    // interruption or pending resume. Repeated 'playing' pushes (per-track state
                    // syncs) never override an interruption.
                    if (interrupted || resumePending) {
                        clearInterruption();
                    }
                    if (!focusRegistered || focusLostTransient) {
                        // Re-request while our focus is temporarily taken (a call started while
                        // paused): the system answers DELAYED, which reports an interruption
                        // instead of letting playback start over the call.
                        requestFocus();
                    }
                } else if (!focusRegistered && !interrupted) {
                    requestFocus();
                }
                break;
            case "paused":
                // Keep the focus registration: it is the only way the post-call GAIN arrives.
                break;
            default:
                teardown();
                break;
        }
        updateRegistrations();
    }

    /**
     * The user paused from a media controller (notification, lock screen, Bluetooth) while an
     * interruption or pending resume was active: do not resume when it ends.
     */
    void onControllerPause() {
        if (!owned || !(interrupted || resumePending)) {
            return;
        }
        boolean wasInterrupted = interrupted;
        clearInterruption();
        if (wasInterrupted) {
            listener.onInterruption("ended", REASON_CANCELLED, false);
        }
        updateRegistrations();
    }

    /** Releases everything (focus, receivers, timers, suppression). */
    void release() {
        teardown();
        owned = false;
    }

    // ---------------------------------------------------------------------------------------
    // Focus

    private void requestFocus() {
        int result;
        try {
            result = audio.requestFocus(focusListener, pauseWhenDucked);
        } catch (RuntimeException e) {
            Log.w(TAG, "requestFocus threw", e);
            result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
        Log.i(TAG, "requestFocus result=" + result + " jsState=" + jsState);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRegistered = true;
            focusLostTransient = false;
        } else if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            // A call (or another focus holder with FLAG_LOCK) is active: we will get GAIN later.
            focusRegistered = true;
            focusLostTransient = true;
            beginInterruption(REASON_DELAYED);
        } else {
            // Fail open: the app keeps playing as it did before this feature existed.
            focusRegistered = false;
        }
    }

    private void abandonFocus() {
        if (!focusRegistered) {
            return;
        }
        try {
            audio.abandonFocus(focusListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "abandonFocus threw", e);
        }
        focusRegistered = false;
        focusLostTransient = false;
    }

    @VisibleForTesting
    void onAudioFocusChange(int focusChange) {
        Log.i(TAG, "onAudioFocusChange " + focusChange + " jsState=" + jsState + " interrupted=" + interrupted);
        if (!owned) {
            return;
        }
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (!pauseWhenDucked) {
                    // Android ducks the app's own players; spoken content is not ducked, which
                    // matches the behaviour before this feature. Nothing to do.
                    return;
                }
                // fall through: pause like a transient loss
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                focusLostTransient = true;
                if ("playing".equals(jsState) && !interrupted) {
                    beginInterruption(REASON_TRANSIENT);
                } else if (resumePending) {
                    // Lost focus again before the app restarted (back-to-back calls).
                    resumePending = false;
                    mainHandler.removeCallbacks(resumeAckTimeout);
                    interrupted = true;
                    interruptionReason = REASON_TRANSIENT;
                    listener.onInterruption("began", REASON_TRANSIENT, true);
                    mainHandler.postDelayed(interruptionCap, INTERRUPTION_CAP_MS);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // Another app took over playback for good: no auto-resume, give focus back.
                boolean wasActive = "playing".equals(jsState) || interrupted || resumePending;
                abandonFocus();
                clearInterruption();
                if (wasActive) {
                    listener.onInterruption("began", REASON_LOSS, false);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                focusLostTransient = false;
                if (interrupted) {
                    checkModeAndFinish();
                }
                break;
            default:
                break;
        }
        updateRegistrations();
    }

    // ---------------------------------------------------------------------------------------
    // Audio mode

    @VisibleForTesting
    void onModeChanged(int mode) {
        if (!owned) {
            return;
        }
        boolean inCall = isCallMode(mode);
        Log.i(TAG, "onModeChanged mode=" + mode + " inCall=" + inCall + " interrupted=" + interrupted);
        if (inCall) {
            // Some VoIP apps switch to communication mode without taking focus; ringing sets
            // MODE_RINGTONE before the ringtone requests focus.
            if ("playing".equals(jsState) && !interrupted) {
                beginInterruption(REASON_CALL);
            }
        } else if (interrupted && !focusLostTransient) {
            finishInterruption();
        }
        updateRegistrations();
    }

    static boolean isCallMode(int mode) {
        // MODE_RINGTONE(1), MODE_IN_CALL(2), MODE_IN_COMMUNICATION(3), MODE_CALL_SCREENING(4),
        // MODE_CALL_REDIRECT(5), MODE_COMMUNICATION_REDIRECT(6).
        return mode >= 1 && mode <= 6;
    }

    private void checkModeAndFinish() {
        mainHandler.removeCallbacks(modePoll);
        if (!interrupted || focusLostTransient) {
            return;
        }
        int mode;
        try {
            mode = audio.getMode();
        } catch (RuntimeException e) {
            mode = AudioManager.MODE_NORMAL;
        }
        if (isCallMode(mode)) {
            // e.g. Pixel call screening hands focus back while the call is still being screened.
            mainHandler.postDelayed(modePoll, MODE_POLL_MS);
            return;
        }
        finishInterruption();
    }

    // ---------------------------------------------------------------------------------------
    // Interruption lifecycle

    private void beginInterruption(@NonNull String reason) {
        interrupted = true;
        interruptionReason = reason;
        resumePending = false;
        mainHandler.removeCallbacks(resumeAckTimeout);
        setSuppressed(true);
        mainHandler.removeCallbacks(interruptionCap);
        mainHandler.postDelayed(interruptionCap, INTERRUPTION_CAP_MS);
        listener.onInterruption("began", reason, true);
    }

    private void finishInterruption() {
        if (!interrupted) {
            return;
        }
        interrupted = false;
        mainHandler.removeCallbacks(interruptionCap);
        mainHandler.removeCallbacks(modePoll);
        resumePending = true;
        mainHandler.removeCallbacks(resumeAckTimeout);
        mainHandler.postDelayed(resumeAckTimeout, RESUME_ACK_TIMEOUT_MS);
        listener.onInterruption("ended", interruptionReason, true);
    }

    private void clearInterruption() {
        interrupted = false;
        resumePending = false;
        mainHandler.removeCallbacks(interruptionCap);
        mainHandler.removeCallbacks(resumeAckTimeout);
        mainHandler.removeCallbacks(modePoll);
        setSuppressed(false);
    }

    private void onInterruptionCap() {
        if (!interrupted) {
            return;
        }
        Log.w(TAG, "interruption exceeded " + INTERRUPTION_CAP_MS + " ms — giving up on auto-resume");
        clearInterruption();
        listener.onInterruption("ended", REASON_EXPIRED, false);
        updateRegistrations();
    }

    private void onResumeAckTimeout() {
        if (!resumePending) {
            return;
        }
        resumePending = false;
        setSuppressed(false);
        updateRegistrations();
    }

    @VisibleForTesting
    void onBecomingNoisy() {
        if (!owned) {
            return;
        }
        if (interrupted || resumePending) {
            // The output went away during the interruption (e.g. the car turned off): resuming
            // would play on the loudspeaker.
            boolean wasInterrupted = interrupted;
            clearInterruption();
            listener.onInterruption(wasInterrupted ? "ended" : "began", REASON_NOISY, false);
        } else if ("playing".equals(jsState)) {
            listener.onInterruption("began", REASON_NOISY, false);
        }
        updateRegistrations();
    }

    private void setSuppressed(boolean suppressed) {
        if (this.suppressed == suppressed) {
            return;
        }
        this.suppressed = suppressed;
        listener.onSuppressionChanged(suppressed);
    }

    private void teardown() {
        clearInterruption();
        abandonFocus();
        updateRegistrations();
    }

    /** Registers the noisy receiver and mode listener only while they can matter. */
    private void updateRegistrations() {
        boolean active = owned && ("playing".equals(jsState) || interrupted || resumePending);
        if (active && !noisyRegistered) {
            try {
                ContextCompat.registerReceiver(context, noisyReceiver,
                        new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                        ContextCompat.RECEIVER_NOT_EXPORTED);
                noisyRegistered = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "registerReceiver(BECOMING_NOISY) failed", e);
            }
        } else if (!active && noisyRegistered) {
            try {
                context.unregisterReceiver(noisyReceiver);
            } catch (RuntimeException e) {
                Log.d(TAG, "unregisterReceiver failed", e);
            }
            noisyRegistered = false;
        }
        if (active && !modeListening) {
            modeListening = audio.addModeListener(modeListener);
        } else if (!active && modeListening) {
            audio.removeModeListener(modeListener);
            modeListening = false;
        }
    }

    @VisibleForTesting
    boolean isInterrupted() {
        return interrupted;
    }

    @VisibleForTesting
    boolean isResumePending() {
        return resumePending;
    }

    @VisibleForTesting
    boolean isFocusRegistered() {
        return focusRegistered;
    }

    /** {@link AudioPort} backed by the real {@link AudioManager}. */
    static final class SystemAudioPort implements AudioPort {
        private final Context context;
        private final AudioManager audioManager;
        private final Handler handler;
        @Nullable
        private AudioFocusRequest focusRequest;
        @Nullable
        private Object platformModeListener;

        SystemAudioPort(Context context, Handler handler) {
            this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
            this.handler = handler;
        }

        @Override
        public int requestFocus(@NonNull AudioManager.OnAudioFocusChangeListener listener, boolean pauseWhenDucked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attributes)
                        .setAcceptsDelayedFocusGain(true)
                        .setWillPauseWhenDucked(pauseWhenDucked)
                        .setOnAudioFocusChangeListener(listener, handler)
                        .build();
                return audioManager.requestAudioFocus(focusRequest);
            }
            return legacyRequest(listener);
        }

        @SuppressWarnings("deprecation")
        private int legacyRequest(AudioManager.OnAudioFocusChangeListener listener) {
            return audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void abandonFocus(@NonNull AudioManager.OnAudioFocusChangeListener listener) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            } else {
                audioManager.abandonAudioFocus(listener);
            }
        }

        @Override
        public int getMode() {
            return audioManager.getMode();
        }

        @Override
        public boolean addModeListener(@NonNull ModeListener listener) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return false;
            }
            try {
                AudioManager.OnModeChangedListener platform = listener::onModeChanged;
                audioManager.addOnModeChangedListener(ContextCompat.getMainExecutor(context), platform);
                platformModeListener = platform;
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "addOnModeChangedListener failed", e);
                return false;
            }
        }

        @Override
        public void removeModeListener(@NonNull ModeListener listener) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || platformModeListener == null) {
                return;
            }
            try {
                audioManager.removeOnModeChangedListener((AudioManager.OnModeChangedListener) platformModeListener);
            } catch (RuntimeException e) {
                Log.d(TAG, "removeOnModeChangedListener failed", e);
            }
            platformModeListener = null;
        }

    }
}
