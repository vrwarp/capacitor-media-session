package io.github.jofr.capacitor.mediasessionplugin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.core.content.ContextCompat;

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
import java.util.List;
import java.util.Map;

@CapacitorPlugin(name = "MediaSession")
public class MediaSessionPlugin extends Plugin {
    private static final String TAG = "MediaSessionPlugin";

    private boolean startServiceOnlyDuringPlayback = true;

    private String title = "";
    private String artist = "";
    private String album = "";
    private Bitmap artwork = null;
    private String playbackState = "none";
    private double duration = 0.0;
    private double position = 0.0;
    private double playbackRate = 1.0;
    private final Map<String, PluginCall> actionHandlers = new HashMap<>();

    private MediaSessionService service = null;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            MediaSessionService.LocalBinder binder = (MediaSessionService.LocalBinder) iBinder;
            service = binder.getService();
            Intent intent = new Intent(getActivity(), getActivity().getClass());
            service.connectAndInitialize(MediaSessionPlugin.this, intent);
            updateServiceMetadata();
            updateServicePlaybackState();
            updateServicePositionState();
            updateAvailableCommands();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d(TAG, "Disconnected from MediaSessionService");
        }
    };

    @Override
    public void load() {
        super.load();

        final String foregroundServiceConfig = getConfig().getString("foregroundService", "");
        if (foregroundServiceConfig.equals("always")) {
            startServiceOnlyDuringPlayback = false;
        }

        if (!startServiceOnlyDuringPlayback) {
            startMediaService();
        }
    }

    public void startMediaService() {
        Intent intent = new Intent(getActivity(), MediaSessionService.class);
        ContextCompat.startForegroundService(getContext(), intent);
        getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void updateServiceMetadata() {
        if (service == null || service.getPlayer() == null) return;

        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album);

        if (artwork != null) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            artwork.compress(Bitmap.CompressFormat.PNG, 100, stream);
            byte[] byteArray = stream.toByteArray();
            metadataBuilder.setArtworkData(byteArray, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
        }

        MediaMetadata metadata = metadataBuilder.build();
        service.getPlayer().setMetadata(metadata);
    }


    private Bitmap urlToBitmap(String url) throws IOException {
        final boolean blobUrl = url.startsWith("blob:");
        if (blobUrl) {
            Log.i(TAG, "Converting Blob URLs to Bitmap for media artwork is not yet supported");
        }

        final boolean httpUrl = url.startsWith("http");
        if (httpUrl) {
            HttpURLConnection connection = (HttpURLConnection) (new URL(url)).openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream inputStream = connection.getInputStream();
            return BitmapFactory.decodeStream(inputStream);
        }

        int base64Index = url.indexOf(";base64,");
        if (base64Index != -1) {
            String base64Data = url.substring(base64Index + 8);
            byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
        }

        return null;
    }

    @PluginMethod
    public void setMetadata(PluginCall call) throws JSONException, IOException {
        title = call.getString("title", title);
        artist = call.getString("artist", artist);
        album = call.getString("album", album);

        final JSArray artworkArray = call.getArray("artwork");
        final List<JSONObject> artworkList = artworkArray.toList();
        for (JSONObject artwork : artworkList) {
            String src = artwork.getString("src");
            if (src != null) {
                this.artwork = urlToBitmap(src);
            }
        }

        if (service != null) { updateServiceMetadata(); };
        call.resolve();
    }

    private void updateServicePlaybackState() {
        if (service == null || service.getPlayer() == null) return;

        int playerState = Player.STATE_IDLE;
        boolean playWhenReady = false;

        if (playbackState.equals("playing")) {
            playerState = Player.STATE_READY;
            playWhenReady = true;
        } else if (playbackState.equals("paused")) {
            playerState = Player.STATE_READY;
            playWhenReady = false;
        }

        service.getPlayer().setPlaybackState(playerState, playWhenReady);
    }

    @PluginMethod
    public void setPlaybackState(PluginCall call) {
        playbackState = call.getString("playbackState", playbackState);

        final boolean playback = playbackState.equals("playing") || playbackState.equals("paused");
        if (startServiceOnlyDuringPlayback && service == null && playback) {
            startMediaService();
        } else if (startServiceOnlyDuringPlayback && service != null && !playback) {
            getContext().unbindService(serviceConnection);
            service = null;
        } else if (service != null) {
            updateServicePlaybackState();
        }
        call.resolve();
    }

    private void updateServicePositionState() {
        if (service == null || service.getPlayer() == null) return;

        float playbackSpeed = playbackRate == 0.0 ? (float) 1.0 : (float) playbackRate;
        long durationMs = Math.round(duration * 1000);
        long positionMs = Math.round(position * 1000);
        service.getPlayer().setPositionState(durationMs, positionMs, playbackSpeed);
    }

    @PluginMethod
    public void setPositionState(PluginCall call) {
        duration = call.getDouble("duration", 0.0);
        position = call.getDouble("position", 0.0);
        playbackRate = call.getFloat("playbackRate", 1.0F);

        if (service != null) { updateServicePositionState(); };
        call.resolve();
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    public void setActionHandler(PluginCall call) {
        call.setKeepAlive(true);
        actionHandlers.put(call.getString("action"), call);

        if (service != null && service.getPlayer() != null) {
            updateAvailableCommands();
        }
    }

    private void updateAvailableCommands() {
        if (service == null || service.getPlayer() == null) return;
        Player.Commands.Builder commandsBuilder = new Player.Commands.Builder();

        if (hasActionHandler("play")) commandsBuilder.add(Player.COMMAND_PLAY_PAUSE);
        if (hasActionHandler("pause")) commandsBuilder.add(Player.COMMAND_PLAY_PAUSE);
        if (hasActionHandler("seekto")) commandsBuilder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
        if (hasActionHandler("seekbackward")) commandsBuilder.add(Player.COMMAND_SEEK_BACK);
        if (hasActionHandler("seekforward")) commandsBuilder.add(Player.COMMAND_SEEK_FORWARD);
        if (hasActionHandler("previoustrack")) commandsBuilder.add(Player.COMMAND_SEEK_TO_PREVIOUS);
        if (hasActionHandler("nexttrack")) commandsBuilder.add(Player.COMMAND_SEEK_TO_NEXT);
        if (hasActionHandler("stop")) commandsBuilder.add(Player.COMMAND_STOP);
        service.getPlayer().setAvailableCommands(commandsBuilder.build());
    }

    public boolean hasActionHandler(String action) {
        return actionHandlers.containsKey(action) && !actionHandlers.get(action).getCallbackId().equals(PluginCall.CALLBACK_ID_DANGLING);
    }

    public void actionCallback(String action) {
        actionCallback(action, new JSObject());
    }

    public void actionCallback(String action, JSObject data) {
        PluginCall call = actionHandlers.get(action);
        if (call != null && !call.getCallbackId().equals(PluginCall.CALLBACK_ID_DANGLING)) {
            data.put("action", action);
            call.resolve(data);
        } else {
            Log.d(TAG, "No handler for action " + action);
        }
    }
}
