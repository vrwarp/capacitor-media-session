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

    private final MediaSessionPlugin plugin;

    public WebViewProxyPlayer(MediaSessionPlugin plugin) {
        super(android.os.Looper.getMainLooper());
        this.plugin = plugin;
    }

    @Override
    protected State getState() {
        // 1. Map Playback State
        String playbackState = plugin.getPlaybackState();
        int media3State = Player.STATE_READY;
        boolean isPlaying = false;
        
        if ("playing".equals(playbackState)) {
            isPlaying = true;
        } else if ("none".equals(playbackState)) {
            media3State = Player.STATE_IDLE;
        }

        // 2. Build Metadata
        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                .setTitle(plugin.getTitle())
                .setArtist(plugin.getArtist())
                .setAlbumTitle(plugin.getAlbum());
                
        byte[] artworkData = plugin.getArtworkData();
        if (artworkData != null) {
            metadataBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
        }

        // 3. Build available commands based on JS listeners
        Player.Commands.Builder commandsBuilder = new Player.Commands.Builder();
        if (plugin.hasActionHandler("play") || plugin.hasActionHandler("pause")) {
            commandsBuilder.add(Player.COMMAND_PLAY_PAUSE);
        }
        if (plugin.hasActionHandler("seekto")) {
            commandsBuilder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
        }
        if (plugin.hasActionHandler("seekforward") || plugin.hasActionHandler("nexttrack")) {
            commandsBuilder.add(Player.COMMAND_SEEK_TO_NEXT);
            commandsBuilder.add(Player.COMMAND_SEEK_FORWARD);
        }
        if (plugin.hasActionHandler("seekbackward") || plugin.hasActionHandler("previoustrack")) {
            commandsBuilder.add(Player.COMMAND_SEEK_TO_PREVIOUS);
            commandsBuilder.add(Player.COMMAND_SEEK_BACK);
        }
        if (plugin.hasActionHandler("stop")) {
            commandsBuilder.add(Player.COMMAND_STOP);
        }

        // 4. Return the built State
        return new State.Builder()
                .setAvailableCommands(commandsBuilder.build())
                .setPlaybackState(media3State)
                .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaylist(java.util.List.of(
                    new MediaItemData.Builder(new Object())
                        .setMediaMetadata(metadataBuilder.build())
                        .setDurationUs(Math.round(plugin.getDuration() * 1000000))
                        .build()
                ))
                .setContentPositionMs(Math.round(plugin.getPosition() * 1000))
                .setPlaybackParameters(new androidx.media3.common.PlaybackParameters((float) plugin.getPlaybackRate()))
                .build();
    }
    public void invalidateProxyState() {
        super.invalidateState();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        if (playWhenReady) {
            plugin.actionCallback("play");
        } else {
            plugin.actionCallback("pause");
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, @Player.Command int seekCommand) {
        if (seekCommand == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM || seekCommand == Player.COMMAND_SEEK_TO_MEDIA_ITEM) {
            JSObject data = new JSObject();
            data.put("seekTime", (double) positionMs / 1000.0);
            plugin.actionCallback("seekto", data);
        } else if (seekCommand == Player.COMMAND_SEEK_FORWARD) {
            plugin.actionCallback("seekforward");
        } else if (seekCommand == Player.COMMAND_SEEK_BACK) {
            plugin.actionCallback("seekbackward");
        } else if (seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
            plugin.actionCallback("nexttrack");
        } else if (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
            plugin.actionCallback("previoustrack");
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        plugin.actionCallback("stop");
        return Futures.immediateVoidFuture();
    }
}
