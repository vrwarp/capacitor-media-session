package io.github.jofr.capacitor.mediasessionplugin;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.Player;
import androidx.media3.session.MediaSession;

import java.util.concurrent.atomic.AtomicInteger;

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
}
