package io.github.jofr.capacitor.mediasessionplugin;

import android.os.Bundle;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;

import com.getcapacitor.JSObject;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class WebViewProxyPlayer extends SimpleBasePlayer {
    private static final String TAG = "WebViewProxyPlayer";

    public interface ActionCallback {
        void onAction(String action, com.getcapacitor.JSObject data);
    }

    private ActionCallback actionCallback;
    private final Object mediaItemUid = new Object();
    private String title = "";
    private String artist = "";
    private String album = "";
    private byte[] artworkData = null;
    private String playbackState = "none";
    private double duration = 0.0;
    private double position = 0.0;
    private double playbackRate = 1.0;
    private java.util.Set<String> supportedActions = new java.util.HashSet<>();

    public WebViewProxyPlayer() {
        super(android.os.Looper.getMainLooper());
    }

    public void setActionCallback(ActionCallback callback) {
        this.actionCallback = callback;
    }

    public void updateState(String title, String artist, String album, byte[] artworkData,
                            String playbackState, double duration, double position,
                            double playbackRate, java.util.Set<String> supportedActions) {
        Log.d(TAG, "updateState() called. playbackState: " + playbackState + ", title: " + title);
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.artworkData = artworkData;
        this.playbackState = playbackState;
        this.duration = duration;
        this.position = position;
        this.playbackRate = playbackRate;
        this.supportedActions = supportedActions;
        invalidateState(); // Forces Media3 to call getState()
    }

    @Override
    protected State getState() {
        Log.d(TAG, "Media3 framework invoking getState(). Constructing state with playbackState=" + playbackState);
        // 1. Map Playback State
        int media3State = Player.STATE_READY;
        boolean isPlaying = false;

        if ("playing".equals(playbackState)) {
            isPlaying = true;
        } else if ("none".equals(playbackState)) {
            media3State = Player.STATE_IDLE;
        }

        // 2. Build Metadata
        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album);

        if (artworkData != null) {
            metadataBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
        }

        // 3. Build available commands based on JS listeners
        Player.Commands.Builder commandsBuilder = new Player.Commands.Builder();

        // Add REQUIRED foundational commands for System UI to recognize the player properly
        commandsBuilder.add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM);
        commandsBuilder.add(Player.COMMAND_GET_METADATA);
        commandsBuilder.add(Player.COMMAND_GET_TIMELINE);
        commandsBuilder.add(Player.COMMAND_GET_DEVICE_VOLUME);

        if (supportedActions.contains("play") || supportedActions.contains("pause")) {
            commandsBuilder.add(Player.COMMAND_PLAY_PAUSE);
        }
        if (supportedActions.contains("seekto")) {
            commandsBuilder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
        }
        if (supportedActions.contains("seekforward") || supportedActions.contains("nexttrack")) {
            commandsBuilder.add(Player.COMMAND_SEEK_TO_NEXT);
            commandsBuilder.add(Player.COMMAND_SEEK_FORWARD);
        }
        if (supportedActions.contains("seekbackward") || supportedActions.contains("previoustrack")) {
            commandsBuilder.add(Player.COMMAND_SEEK_TO_PREVIOUS);
            commandsBuilder.add(Player.COMMAND_SEEK_BACK);
        }
        if (supportedActions.contains("stop")) {
            commandsBuilder.add(Player.COMMAND_STOP);
        }

        // 4. Return the built State
        return new State.Builder()
                .setAvailableCommands(commandsBuilder.build())
                .setPlaybackState(media3State)
                .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaylist(java.util.List.of(
                    new MediaItemData.Builder(mediaItemUid)
                        .setMediaMetadata(metadataBuilder.build())
                        .setDurationUs(Math.round(duration * 1000000))
                        .build()
                ))
                .setContentPositionMs(Math.round(position * 1000))
                .setPlaybackParameters(new androidx.media3.common.PlaybackParameters((float) playbackRate))
                .build();
    }
    public void invalidateProxyState() {
        super.invalidateState();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        Log.i(TAG, "OS -> Player: handleSetPlayWhenReady(" + playWhenReady + ")");
        // Optimistically update internal state to prevent UI snapping back
        this.playbackState = playWhenReady ? "playing" : "paused";
        invalidateState(); // Force Media3 to read the new state immediately

        if (actionCallback != null) {
            actionCallback.onAction(playWhenReady ? "play" : "pause", new JSObject());
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, @Player.Command int seekCommand) {
        Log.i(TAG, "OS -> Player: handleSeek(positionMs=" + positionMs + ", command=" + seekCommand + ")");
        if (actionCallback != null) {
            if (seekCommand == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM || seekCommand == Player.COMMAND_SEEK_TO_MEDIA_ITEM) {
                JSObject data = new JSObject();
                data.put("seekTime", (double) positionMs / 1000.0);
                actionCallback.onAction("seekto", data);
            } else if (seekCommand == Player.COMMAND_SEEK_FORWARD) {
                actionCallback.onAction("seekforward", new JSObject());
            } else if (seekCommand == Player.COMMAND_SEEK_BACK) {
                actionCallback.onAction("seekbackward", new JSObject());
            } else if (seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                actionCallback.onAction("nexttrack", new JSObject());
            } else if (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                actionCallback.onAction("previoustrack", new JSObject());
            }
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        Log.i(TAG, "OS -> Player: handleStop()");
        if (actionCallback != null) {
            actionCallback.onAction("stop", new JSObject());
        }
        return Futures.immediateVoidFuture();
    }
}
