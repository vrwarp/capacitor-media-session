package io.github.jofr.capacitor.mediasessionplugin;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import com.getcapacitor.JSObject;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Collections;

public class WebViewProxyPlayer extends SimpleBasePlayer {
    private final MediaSessionPlugin plugin;
    private State currentState;
    private State.Builder stateBuilder;

    public WebViewProxyPlayer(MediaSessionPlugin plugin) {
        super(android.os.Looper.getMainLooper());
        this.plugin = plugin;
    }

    @Override
    protected State getState() {
        if (currentState == null) {
            stateBuilder = new State.Builder()
                .setAvailableCommands(new Player.Commands.Builder().build());
            currentState = stateBuilder.build();
        }
        return currentState;
    }

    private void updateState() {
        if (stateBuilder != null) {
            currentState = stateBuilder.build();
            invalidateState();
        }
    }

    public void setMetadata(MediaMetadata metadata) {
        getState();
        stateBuilder.setPlaylist(Collections.singletonList(
                new MediaItemData.Builder("WebViewMediaItem")
                        .setMediaItem(new MediaItem.Builder().setMediaId("WebViewMediaItem").build())
                        .setMediaMetadata(metadata)
                        .build()
        ));
        updateState();
    }

    public void setPlaybackState(int playerState, boolean playWhenReady) {
        getState();
        if (currentState.playlist.isEmpty() && (playerState == Player.STATE_READY || playerState == Player.STATE_BUFFERING)) {
            stateBuilder.setPlaylist(Collections.singletonList(
                new MediaItemData.Builder("WebViewMediaItem")
                        .setMediaItem(new MediaItem.Builder().setMediaId("WebViewMediaItem").build())
                        .build()
            ));
        }
        stateBuilder.setPlaybackState(playerState)
                    .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
        updateState();
    }

    public void setPositionState(long durationMs, long positionMs, float playbackSpeed) {
        getState();
        PositionSupplier positionSupplier = new PositionSupplier() {
            @Override
            public long get() {
                return positionMs;
            }
        };

        MediaMetadata currentMetadata = currentState.playlist.isEmpty() ? new MediaMetadata.Builder().build() : currentState.playlist.get(0).mediaMetadata;

        stateBuilder.setPlaylist(Collections.singletonList(
                new MediaItemData.Builder("WebViewMediaItem")
                        .setMediaItem(new MediaItem.Builder().setMediaId("WebViewMediaItem").build())
                        .setMediaMetadata(currentMetadata)
                        .setDurationUs(durationMs * 1000)
                        .build()
        ))
        .setContentPositionMs(positionSupplier)
        .setPlaybackParameters(new PlaybackParameters(playbackSpeed));

        updateState();
    }

    public void setAvailableCommands(Player.Commands commands) {
        getState();
        stateBuilder.setAvailableCommands(commands);
        updateState();
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
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        if (seekCommand == Player.COMMAND_SEEK_BACK) {
            plugin.actionCallback("seekbackward");
        } else if (seekCommand == Player.COMMAND_SEEK_FORWARD) {
            plugin.actionCallback("seekforward");
        } else if (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS) {
            plugin.actionCallback("previoustrack");
        } else if (seekCommand == Player.COMMAND_SEEK_TO_NEXT) {
            plugin.actionCallback("nexttrack");
        } else {
            JSObject data = new JSObject();
            data.put("seekTime", (double) positionMs / 1000.0);
            plugin.actionCallback("seekto", data);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        plugin.actionCallback("stop");
        return Futures.immediateVoidFuture();
    }
}
