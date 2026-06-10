package io.github.jofr.capacitor.mediasessionplugin;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.session.MediaSession;

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
        player = new WebViewProxyPlayer();
        mediaSession = new MediaSession.Builder(this, player).build();
        // Register the session explicitly: it is usually only registered lazily through
        // onGetSession() when a Media3 controller connects, but the plugin connects through the
        // local binder instead. Without this call the service would never show the media
        // notification or promote itself to a foreground service.
        addSession(mediaSession);
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        if (intent != null && intent.getAction() == null) {
            return localBinder;
        }
        // Media3 controllers (androidx.media3.session.MediaSessionService action) and legacy
        // media browsers (android.media.browse.MediaBrowserService action) are handled by the
        // superclass.
        return super.onBind(intent);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Destroying service and releasing media session");
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
