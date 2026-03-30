package io.github.jofr.capacitor.mediasessionplugin;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.session.MediaSession;

public class MediaSessionService extends androidx.media3.session.MediaSessionService {
    private static final String TAG = "MediaSessionService";

    private MediaSession mediaSession;
    private WebViewProxyPlayer player;

    private final IBinder binder = new LocalBinder();

    public final class LocalBinder extends Binder {
        MediaSessionService getService() {
            return MediaSessionService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate() fired. Building MediaSession.");
        this.player = new WebViewProxyPlayer();
        this.mediaSession = new MediaSession.Builder(this, player).build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (androidx.media3.session.MediaSessionService.SERVICE_INTERFACE.equals(action)) {
            return super.onBind(intent);
        }
        return binder;
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy() fired. Releasing MediaSession.");
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    public WebViewProxyPlayer getPlayer() {
        return player;
    }
}
