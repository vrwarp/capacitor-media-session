package io.github.jofr.capacitor.mediasessionplugin;

import android.content.Intent;
import android.os.IBinder;
import android.os.Binder;

import androidx.annotation.Nullable;
import androidx.media3.session.MediaSession;

public class MediaSessionService extends androidx.media3.session.MediaSessionService {
    private MediaSession mediaSession;
    private WebViewProxyPlayer player;

    private final IBinder binder = new LocalBinder();

    public final class LocalBinder extends Binder {
        MediaSessionService getService() {
            return MediaSessionService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (super.onBind(intent) != null) {
            return super.onBind(intent);
        }
        return binder;
    }

    public void connectAndInitialize(MediaSessionPlugin plugin, Intent intent) {
        player = new WebViewProxyPlayer(plugin);

        mediaSession = new MediaSession.Builder(this, player).setId("WebViewMediaSession-" + System.currentTimeMillis()).build();
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    public WebViewProxyPlayer getPlayer() {
        return player;
    }
}
