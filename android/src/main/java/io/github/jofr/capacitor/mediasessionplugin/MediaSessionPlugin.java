package io.github.jofr.capacitor.mediasessionplugin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@CapacitorPlugin(name = "MediaSession")
public class MediaSessionPlugin extends Plugin {
    private static final String TAG = "MediaSessionPlugin";

    /**
     * Artwork is scaled down so its long edge is at most this many pixels before it is handed to
     * Media3. Oversized bitmaps crossing the Binder to the platform MediaSession have crashed
     * com.android.bluetooth's AVRCP layer, and the system downscales artwork for the
     * notification/lock screen anyway, so the cap is lossless in practice.
     */
    private static final int MAX_ARTWORK_DIMENSION = 512;

    /** JPEG quality for the encoded artwork (cover art is opaque, so no alpha is lost). */
    private static final int ARTWORK_JPEG_QUALITY = 85;

    private boolean startServiceOnlyDuringPlayback = true;

    private String title = "";
    private String artist = "";
    private String album = "";
    private byte[] artworkData = null;
    private String playbackState = "none";
    private double duration = 0.0;
    private double position = 0.0;
    private double playbackRate = 1.0;
    private final Map<String, PluginCall> actionHandlers = new HashMap<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaSessionService service = null;
    private boolean serviceBindingRequested = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Log.i(TAG, "onServiceConnected: binding proxy player action callback and pushing state");
            MediaSessionService.LocalBinder binder = (MediaSessionService.LocalBinder) iBinder;
            service = binder.getService();
            WebViewProxyPlayer player = service.getPlayer();
            if (player != null) {
                player.setActionCallback(MediaSessionPlugin.this::onPlayerAction);
            }
            updateServiceState();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d(TAG, "Disconnected from MediaSessionService");
            service = null;
            serviceBindingRequested = false;
        }
    };

    @Override
    public void load() {
        super.load();

        final String foregroundServiceConfig = getConfig().getString("foregroundService", "");
        if (foregroundServiceConfig.equals("always")) {
            startServiceOnlyDuringPlayback = false;
        }
        Log.i(TAG, "load: foregroundService='" + foregroundServiceConfig
                + "' startServiceOnlyDuringPlayback=" + startServiceOnlyDuringPlayback);

        if (!startServiceOnlyDuringPlayback) {
            startMediaService();
        }
    }

    private void startMediaService() {
        if (serviceBindingRequested) {
            return;
        }
        serviceBindingRequested = true;
        Log.i(TAG, "startMediaService: bindService(MediaSessionService, BIND_AUTO_CREATE)");
        Intent intent = new Intent(getContext(), MediaSessionService.class);
        getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void stopMediaService() {
        if (serviceBindingRequested) {
            try {
                getContext().unbindService(serviceConnection);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "Service was not bound when trying to unbind", e);
            }
        }
        serviceBindingRequested = false;
        service = null;
        // Media3 may have started the service (to promote it to a foreground service during
        // playback), in which case unbinding alone would not destroy it.
        getContext().stopService(new Intent(getContext(), MediaSessionService.class));
    }

    /**
     * Pushes the current metadata, playback and position state to the proxy player on the main
     * thread (the player must only be accessed from its application looper).
     */
    private void updateServiceState() {
        final String playbackState = this.playbackState;
        final String title = this.title;
        final String artist = this.artist;
        final String album = this.album;
        final byte[] artworkData = this.artworkData;
        final double duration = this.duration;
        final double position = this.position;
        final double playbackRate = this.playbackRate;
        final Set<String> supportedActions = new HashSet<>(actionHandlers.keySet());

        mainHandler.post(() -> {
            MediaSessionService service = this.service;
            WebViewProxyPlayer player = service != null ? service.getPlayer() : null;
            if (player == null) {
                Log.w(TAG, "updateServiceState: service not bound yet — dropping state update (playbackState="
                        + playbackState + ", title='" + title + "')");
                return;
            }
            player.updateSessionState(
                playbackState,
                title,
                artist,
                album,
                artworkData,
                duration,
                position,
                playbackRate,
                supportedActions
            );
        });
    }

    /**
     * Pure dimension math (unit-tested): scale {@code width}x{@code height} so the long edge is at
     * most {@code maxEdge}, preserving aspect ratio and never upscaling. Returns
     * {@code [width, height]} unchanged when already within bounds or for degenerate input.
     */
    static int[] computeScaledDimensions(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0) {
            return new int[] { width, height };
        }
        final int longEdge = Math.max(width, height);
        if (longEdge <= maxEdge) {
            return new int[] { width, height };
        }
        final double scale = (double) maxEdge / (double) longEdge;
        return new int[] {
            Math.max(1, (int) Math.round(width * scale)),
            Math.max(1, (int) Math.round(height * scale))
        };
    }

    private Bitmap scaleBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        int[] dims = computeScaledDimensions(bitmap.getWidth(), bitmap.getHeight(), MAX_ARTWORK_DIMENSION);
        if (dims[0] == bitmap.getWidth() && dims[1] == bitmap.getHeight()) {
            return bitmap;
        }
        return Bitmap.createScaledBitmap(bitmap, dims[0], dims[1], true);
    }

    private byte[] bitmapToArtworkData(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        Bitmap scaled = scaleBitmap(bitmap);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        // JPEG (not PNG): cover art is opaque, and JPEG keeps the encoded artwork comfortably under
        // the Binder transaction limit on its way to the platform MediaSession.
        scaled.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, stream);
        return stream.toByteArray();
    }

    private byte[] urlToArtworkData(String url) throws IOException {
        if (url.startsWith("blob:")) {
            Log.i(TAG, "Converting Blob URLs to artwork is not yet supported");
            return null;
        }

        if (url.startsWith("http")) {
            HttpURLConnection connection = (HttpURLConnection) (new URL(url)).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoInput(true);
            connection.connect();
            try (InputStream inputStream = connection.getInputStream()) {
                return bitmapToArtworkData(BitmapFactory.decodeStream(inputStream));
            } finally {
                connection.disconnect();
            }
        }

        int base64Index = url.indexOf(";base64,");
        if (base64Index != -1) {
            String base64Data = url.substring(base64Index + 8);
            byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);
            return bitmapToArtworkData(BitmapFactory.decodeByteArray(decoded, 0, decoded.length));
        }

        return null;
    }

    @PluginMethod
    public void setMetadata(PluginCall call) throws JSONException {
        title = call.getString("title", title);
        artist = call.getString("artist", artist);
        album = call.getString("album", album);

        final JSArray artworkArray = call.getArray("artwork");
        if (artworkArray != null) {
            final List<JSONObject> artworkList = artworkArray.toList();
            for (JSONObject artwork : artworkList) {
                String src = artwork.optString("src", null);
                if (src == null) {
                    continue;
                }
                try {
                    byte[] data = urlToArtworkData(src);
                    if (data != null) {
                        this.artworkData = data;
                    }
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "Could not load artwork from " + src, e);
                }
            }
        }

        updateServiceState();
        call.resolve();
    }

    @PluginMethod
    public void setPlaybackState(PluginCall call) {
        playbackState = call.getString("playbackState", playbackState);

        final boolean playback = playbackState.equals("playing") || playbackState.equals("paused");
        if (playback && service == null) {
            startMediaService();
        } else if (!playback && startServiceOnlyDuringPlayback) {
            stopMediaService();
        } else {
            updateServiceState();
        }
        call.resolve();
    }

    @PluginMethod
    public void setPositionState(PluginCall call) {
        duration = call.getDouble("duration", 0.0);
        position = call.getDouble("position", 0.0);
        playbackRate = call.getFloat("playbackRate", 1.0F);

        updateServiceState();
        call.resolve();
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    public void setActionHandler(PluginCall call) {
        final String action = call.getString("action");
        if (action == null || action.isEmpty()) {
            call.reject("action is required");
            return;
        }

        final boolean remove = call.getBoolean("removeHandler", false);

        // Always release any previously stored kept-alive call for this action before replacing or
        // removing it; otherwise re-registration would leak the old RETURN_CALLBACK PluginCall.
        PluginCall previous = actionHandlers.remove(action);
        if (previous != null && previous.isKeptAlive() && !previous.isReleased()) {
            previous.release(getBridge());
        }

        if (remove) {
            // This call carries no live JS handler (the null callback was translated into a
            // removeHandler flag by the TS wrapper), so resolve it instead of keeping it alive.
            Log.d(TAG, "setActionHandler: removed '" + action + "' supportedActions=" + actionHandlers.keySet());
            updateServiceState();
            call.resolve();
            return;
        }

        call.setKeepAlive(true);
        actionHandlers.put(action, call);
        Log.d(TAG, "setActionHandler: registered '" + action + "' supportedActions=" + actionHandlers.keySet());
        updateServiceState();
    }

    public boolean hasActionHandler(String action) {
        PluginCall call = actionHandlers.get(action);
        return call != null && !call.getCallbackId().equals(PluginCall.CALLBACK_ID_DANGLING);
    }

    private void onPlayerAction(String action, Double seekTime, Double seekOffset) {
        JSObject data = new JSObject();
        if (seekTime != null) {
            data.put("seekTime", seekTime);
        }
        if (seekOffset != null) {
            data.put("seekOffset", seekOffset);
        }
        actionCallback(action, data);
    }

    public void actionCallback(String action) {
        actionCallback(action, new JSObject());
    }

    public void actionCallback(String action, JSObject data) {
        if (hasActionHandler(action)) {
            data.put("action", action);
            actionHandlers.get(action).resolve(data);
        } else {
            Log.w(TAG, "actionCallback DROPPED: no live handler for '" + action
                    + "' (registration race or never registered) — control will do nothing");
        }
    }

    @Override
    protected void handleOnDestroy() {
        stopMediaService();
        super.handleOnDestroy();
    }
}
