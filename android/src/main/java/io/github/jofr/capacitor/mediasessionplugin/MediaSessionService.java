package io.github.jofr.capacitor.mediasessionplugin;

import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.Player;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.getcapacitor.JSObject;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.jofr.capacitor.mediasessionplugin.CustomActions.CustomActionSpec;

/**
 * Foreground service hosting the Media3 {@link MediaSession}. Media3 takes care of the media
 * notification, media button handling and foreground service lifecycle based on the state of
 * the attached {@link WebViewProxyPlayer}.
 *
 * Besides the Media3/media browser binding handled by the superclass, the service supports a
 * local binding (an intent without action, used by {@link MediaSessionPlugin}) that hands out
 * direct access to the service so the plugin can push state into the proxy player.
 */
public class MediaSessionService extends androidx.media3.session.MediaSessionService {
    private static final String TAG = "MediaSessionService";

    // Media3 requires every live MediaSession in the process to have a unique id; the default is
    // the empty string, which collides ("Session ID must be unique") if a second service instance
    // builds a session before the previous one is released — a service-recreate race on device,
    // and routine across unit tests sharing a JVM.
    private static final AtomicInteger SESSION_COUNTER = new AtomicInteger(0);

    @Nullable
    private MediaSession mediaSession;
    @Nullable
    private WebViewProxyPlayer player;

    /** Back-reference to the plugin so custom-command taps can be routed to its JS handlers. */
    @Nullable
    private MediaSessionPlugin plugin;

    /**
     * Current ordered custom-action buttons published in the session's custom layout. Kept so a
     * newly connecting controller (via {@link CustomActionsCallback#onConnect}) can be granted the
     * matching session commands and layout. Only mutated on the main looper.
     */
    private ImmutableList<CommandButton> customButtons = ImmutableList.of();

    /** Session mutation must happen on the app/main looper, mirroring the proxy-player discipline. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** The session callback, retained so tests can drive onConnect/onCustomCommand directly. */
    private final CustomActionsCallback sessionCallback = new CustomActionsCallback();

    private final IBinder localBinder = new LocalBinder();

    public final class LocalBinder extends Binder {
        MediaSessionService getService() {
            return MediaSessionService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Make the silently-swallowed Android 12+ foreground-service-start denial visible. Media3
        // catches ForegroundServiceStartNotAllowedException internally and (with no listener) posts
        // NOTHING — so "no notification" and "FGS denied" look identical in logcat without this hook.
        setListener(new Listener() {
            @Override
            public void onForegroundServiceStartNotAllowedException() {
                Log.e(TAG, "FGS-DENIED: Android 12+ refused startForegroundService (background start). "
                        + "The media notification will NOT appear. "
                        + "playbackState=" + (player != null ? player.getPlaybackState() : -1)
                        + " playWhenReady=" + (player != null && player.getPlayWhenReady()));
            }
        });

        // Top cause on Android 13+: POST_NOTIFICATIONS not granted -> the FGS notification is
        // suppressed even though startForeground succeeds. Logged once so it is not a silent cause.
        Log.i(TAG, "onCreate: notificationsEnabled="
                + NotificationManagerCompat.from(this).areNotificationsEnabled());

        final String sessionId = "MediaSession-" + SESSION_COUNTER.getAndIncrement();
        player = new WebViewProxyPlayer();
        try {
            mediaSession = new MediaSession.Builder(this, player)
                    .setId(sessionId)
                    .setCallback(sessionCallback)
                    .build();
            // Register the session explicitly: it is usually only registered lazily through
            // onGetSession() when a Media3 controller connects, but the plugin connects through the
            // local binder instead. Without this call Media3's MediaNotificationManager would never
            // attach, so the service would never show the media notification or promote itself to a
            // foreground service.
            addSession(mediaSession);
            Log.i(TAG, "onCreate: built and added MediaSession id=" + sessionId);
        } catch (IllegalStateException e) {
            Log.e(TAG, "onCreate: building/adding MediaSession id=" + sessionId
                    + " FAILED (likely a prior session not released — 'Session ID must be unique')", e);
            throw e;
        }
    }

    /**
     * Media3 calls this whenever it (re)posts or updates the foreground media notification — the
     * actual "the notification was posted" signal (complements FGS-DENIED, which is the failure
     * side). If getState looks correct but no notification appears and this never logs, the post
     * itself was suppressed (channel/POST_NOTIFICATIONS/OEM).
     */
    @Override
    public void onUpdateNotification(MediaSession session, boolean startInForegroundRequired) {
        Log.i(TAG, "onUpdateNotification: posting media notification (startInForegroundRequired="
                + startInForegroundRequired + ")");
        super.onUpdateNotification(session, startInForegroundRequired);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // Preserve Media3's media-button / foreground handling, but do not let the OS resurrect a
        // sessionless zombie: this proxy mirrors WebView-produced audio and has no native resume
        // path, so a restarted service would only sit idle.
        Log.i(TAG, "onStartCommand: action=" + (intent != null ? intent.getAction() : "null")
                + " flags=" + flags + " startId=" + startId + " -> START_NOT_STICKY");
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // WebView-produced audio cannot resume from a backgrounded/killed service, so when the task
        // is swiped away and we are not actively playing, stop the service rather than leaving a
        // dead notification behind.
        final boolean stopping = player == null
                || player.getPlaybackState() == Player.STATE_IDLE
                || !player.getPlayWhenReady();
        Log.i(TAG, "onTaskRemoved: playbackState=" + (player != null ? player.getPlaybackState() : -1)
                + " playWhenReady=" + (player != null && player.getPlayWhenReady())
                + " -> " + (stopping ? "stopSelf()" : "keep running"));
        if (stopping) {
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            Log.i(TAG, "onBind: actionless intent -> LocalBinder");
            return localBinder;
        }
        // Media3 controllers (androidx.media3.session.MediaSessionService action) and legacy
        // media browsers (android.media.browse.MediaBrowserService action) are handled by the
        // superclass.
        Log.i(TAG, "onBind: action=" + action + " -> super (Media3/media browser)");
        return super.onBind(intent);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: releasing media session");
        clearListener();
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
            player = null;
        }
        super.onDestroy();
    }

    @Nullable
    public WebViewProxyPlayer getPlayer() {
        return player;
    }

    @Nullable
    MediaSession getMediaSession() {
        return mediaSession;
    }

    /** Test accessor for the session callback so onConnect/onCustomCommand can be driven directly. */
    MediaSession.Callback getSessionCallback() {
        return sessionCallback;
    }

    /** Registers the plugin so custom-command taps can be routed back to its JS handlers. */
    public void setPlugin(@Nullable MediaSessionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Marshals a custom-command {@link Bundle} into a {@link JSObject} so the per-tap arguments reach
     * JS as {@code ActionDetails.data}. Each key is copied via the matching {@code JSObject.put}
     * overload (String, boolean, int/long, double/float); null values are skipped and any other type
     * falls back to {@code String.valueOf(...)}. A null/empty bundle yields an empty object.
     */
    static JSObject bundleToJSObject(@Nullable Bundle args) {
        JSObject obj = new JSObject();
        if (args == null) {
            return obj;
        }
        for (String key : args.keySet()) {
            Object value = args.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof String) {
                obj.put(key, (String) value);
            } else if (value instanceof Boolean) {
                obj.put(key, (Boolean) value);
            } else if (value instanceof Integer) {
                obj.put(key, (Integer) value);
            } else if (value instanceof Long) {
                obj.put(key, (Long) value);
            } else if (value instanceof Double) {
                obj.put(key, (Double) value);
            } else if (value instanceof Float) {
                obj.put(key, (Float) value);
            } else {
                obj.put(key, String.valueOf(value));
            }
        }
        return obj;
    }

    /**
     * Builds a Media3 {@link CommandButton} for one custom action. The icon is passed as a built-in
     * {@link CommandButton} {@code ICON_*} constant (Media3 resolves the bundled drawable); an
     * {@code ICON_UNDEFINED} spec yields a button without a built-in icon.
     */
    private static CommandButton buildButton(CustomActionSpec spec) {
        CommandButton.Builder builder = (spec.iconConstant != CommandButton.ICON_UNDEFINED)
                ? new CommandButton.Builder(spec.iconConstant)
                : new CommandButton.Builder();
        return builder
                .setSessionCommand(new SessionCommand(spec.id, Bundle.EMPTY))
                .setDisplayName(spec.label != null ? spec.label : "")
                .setEnabled(true)
                .build();
    }

    /**
     * Builds a {@link SessionCommands} containing the default session commands plus one custom
     * {@link SessionCommand} per registered custom action. Used both for newly connecting
     * controllers ({@link CustomActionsCallback#onConnect}) and for re-granting commands to
     * already-connected controllers when actions change mid-session.
     */
    private static SessionCommands sessionCommandsFor(Collection<CommandButton> buttons) {
        SessionCommands.Builder builder = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon();
        for (CommandButton button : buttons) {
            if (button.sessionCommand != null) {
                builder.add(button.sessionCommand);
            }
        }
        return builder.build();
    }

    /**
     * Rebuilds the custom layout from the current ordered custom-action specs and publishes it to
     * the session, re-granting the matching session commands to every already-connected controller
     * so mid-session additions take effect immediately. Posts to the main looper; all session
     * mutation happens there.
     */
    public void updateCustomActions(List<CustomActionSpec> specs) {
        final List<CustomActionSpec> snapshot = new ArrayList<>(specs);
        mainHandler.post(() -> {
            MediaSession session = this.mediaSession;
            if (session == null) {
                Log.w(TAG, "updateCustomActions: no media session — dropping " + snapshot.size() + " custom action(s)");
                return;
            }

            List<CommandButton> buttons = new ArrayList<>(snapshot.size());
            for (CustomActionSpec spec : snapshot) {
                buttons.add(buildButton(spec));
            }
            customButtons = ImmutableList.copyOf(buttons);

            session.setCustomLayout(customButtons);

            // Already-connected controllers were granted their command set at connect time, so
            // re-grant it now to include any newly registered custom commands.
            SessionCommands sessionCommands = sessionCommandsFor(customButtons);
            for (MediaSession.ControllerInfo controller : session.getConnectedControllers()) {
                session.setAvailableCommands(controller, sessionCommands,
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS);
            }
            Log.d(TAG, "updateCustomActions: published " + customButtons.size() + " custom button(s)");
        });
    }

    /**
     * {@link MediaSession.Callback} that grants the custom session commands on connect and routes
     * custom-command taps back to the plugin's JS handlers.
     */
    private final class CustomActionsCallback implements MediaSession.Callback {
        @Override
        @NonNull
        public MediaSession.ConnectionResult onConnect(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller) {
            // Seed from the defaults so standard transport controls are not dropped, then add a
            // session command per currently-registered custom action and publish the layout.
            SessionCommands sessionCommands = sessionCommandsFor(customButtons);
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setCustomLayout(customButtons)
                    .build();
        }

        @Override
        @NonNull
        public ListenableFuture<SessionResult> onCustomCommand(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull SessionCommand sessionCommand,
                @NonNull Bundle args) {
            MediaSessionPlugin plugin = MediaSessionService.this.plugin;
            if (plugin != null && sessionCommand.customAction != null && !sessionCommand.customAction.isEmpty()) {
                // Marshal the controller-supplied args bundle (and any extras baked into the
                // command) into the per-tap data payload that surfaces as ActionDetails.data in JS.
                JSObject data = bundleToJSObject(args);
                Bundle extras = sessionCommand.customExtras;
                if (extras != null && !extras.isEmpty()) {
                    JSObject extrasData = bundleToJSObject(extras);
                    for (java.util.Iterator<String> it = extrasData.keys(); it.hasNext(); ) {
                        String key = it.next();
                        // The controller args bundle takes precedence over the command's seed extras.
                        if (!data.has(key)) {
                            data.put(key, extrasData.opt(key));
                        }
                    }
                }
                plugin.actionCallback(sessionCommand.customAction, data);
            } else {
                Log.w(TAG, "onCustomCommand: no plugin or empty customAction — dropping '"
                        + sessionCommand.customAction + "'");
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        }
    }
}
