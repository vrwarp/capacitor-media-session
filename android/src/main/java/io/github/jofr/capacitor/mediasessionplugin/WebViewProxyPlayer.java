package io.github.jofr.capacitor.mediasessionplugin;

import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Media3 {@link Player} implementation that does not play anything itself but mirrors the
 * playback state of the media element inside the Capacitor WebView. State changes reported
 * from JavaScript are applied via {@link #updateSessionState}, and player commands issued by
 * the system (notification, Bluetooth, hardware keys, ...) are forwarded back to JavaScript
 * through the {@link ActionCallback}.
 *
 * The JavaScript side stays the source of truth: command handlers only update the local state
 * optimistically where required for responsive system UI (play/pause button, seek bar) and
 * otherwise wait for JavaScript to confirm the change via the plugin methods.
 */
public class WebViewProxyPlayer extends SimpleBasePlayer {
    private static final String TAG = "WebViewProxyPlayer";

    private static final String MEDIA_ID = "capacitor-media-session";

    /**
     * Amount, in milliseconds, by which "seekforward"/"seekbackward" move the playback position.
     * These are reported to Media3 via {@link State.Builder#setSeekForwardIncrementMs}/
     * {@link State.Builder#setSeekBackIncrementMs} and delivered to the JavaScript handler as
     * {@code seekOffset} (in seconds) so the WebView can apply the same increment. A symmetric
     * 10 s keeps behavior predictable across the notification, Bluetooth and hardware-key controls.
     */
    static final long SEEK_FORWARD_INCREMENT_MS = 10_000L;
    static final long SEEK_BACK_INCREMENT_MS = 10_000L;

    /** Receives media session actions that should be handled by the JavaScript side. */
    public interface ActionCallback {
        void onAction(String action, @Nullable Double seekTime, @Nullable Double seekOffset);
    }

    /** Stable playlist UIDs ("previous" and "next" are placeholders, see {@link #getState()}). */
    private static final Object PREVIOUS_ITEM_UID = new Object();
    private static final Object CURRENT_ITEM_UID = new Object();
    private static final Object NEXT_ITEM_UID = new Object();

    @Nullable
    private ActionCallback actionCallback = null;

    private String title = "";
    private String artist = "";
    private String album = "";
    @Nullable
    private byte[] artworkData = null;
    private String playbackState = "none";
    private double duration = 0.0;
    private double position = 0.0;
    private double playbackRate = 1.0;
    private Set<String> supportedActions = new HashSet<>();

    /**
     * Built {@link MediaMetadata} for the current title/artist/album/artwork, or {@code null} when
     * it must be rebuilt. {@link #getState()} runs on every state invalidation AND position tick;
     * without this cache each call re-built the metadata and re-cloned the artwork byte array
     * (~50–150 KB) even when only the position changed. Invalidated by {@link #updateSessionState}
     * whenever one of its inputs changes; accessed only on the application looper like all other
     * player state.
     */
    @Nullable
    private MediaMetadata cachedMediaMetadata = null;

    public WebViewProxyPlayer() {
        super(Looper.getMainLooper());
    }

    public void setActionCallback(@Nullable ActionCallback actionCallback) {
        this.actionCallback = actionCallback;
    }

    /**
     * Applies the state reported by the JavaScript media session. Must be called on the main
     * thread (the application looper of this player).
     */
    public void updateSessionState(
        String playbackState,
        String title,
        String artist,
        String album,
        @Nullable byte[] artworkData,
        double duration,
        double position,
        double playbackRate,
        Set<String> supportedActions
    ) {
        // Invalidate the cached MediaMetadata only when one of its inputs changed, so
        // position/state-only updates (the steady-state per-second tick) reuse the built instance.
        // The artwork comparison is by reference on purpose: the plugin re-fetches into a NEW array
        // whenever the artwork changes and re-passes the same array otherwise.
        if (!Objects.equals(this.title, title)
                || !Objects.equals(this.artist, artist)
                || !Objects.equals(this.album, album)
                || this.artworkData != artworkData) {
            cachedMediaMetadata = null;
        }
        this.playbackState = playbackState;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.artworkData = artworkData;
        this.duration = duration;
        this.position = position;
        this.playbackRate = playbackRate;
        this.supportedActions = new HashSet<>(supportedActions);
        invalidateState();
    }

    @Override
    protected State getState() {
        final boolean playing = playbackState.equals("playing");
        final boolean paused = playbackState.equals("paused");
        final int media3PlaybackState = (playing || paused) ? Player.STATE_READY : Player.STATE_IDLE;

        final Player.Commands.Builder commands = new Player.Commands.Builder()
            .addAll(
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_RELEASE
            );
        if (supportedActions.contains("play") || supportedActions.contains("pause")) {
            commands.add(Player.COMMAND_PLAY_PAUSE);
        }
        if (supportedActions.contains("seekto")) {
            commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
            commands.add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION);
        }
        if (supportedActions.contains("seekforward")) {
            commands.add(Player.COMMAND_SEEK_FORWARD);
        }
        if (supportedActions.contains("seekbackward")) {
            commands.add(Player.COMMAND_SEEK_BACK);
        }
        if (supportedActions.contains("nexttrack")) {
            commands.add(Player.COMMAND_SEEK_TO_NEXT);
            commands.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM);
        }
        if (supportedActions.contains("previoustrack")) {
            commands.add(Player.COMMAND_SEEK_TO_PREVIOUS);
            commands.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM);
        }
        if (supportedActions.contains("nexttrack") || supportedActions.contains("previoustrack")) {
            commands.add(Player.COMMAND_SEEK_TO_MEDIA_ITEM);
        }
        if (supportedActions.contains("stop")) {
            commands.add(Player.COMMAND_STOP);
        }

        if (cachedMediaMetadata == null) {
            cachedMediaMetadata = buildMediaMetadata();
        }
        final MediaMetadata mediaMetadata = cachedMediaMetadata;

        // Non-finite and absurd values must never reach the Media3 timeline: JS can hand us
        // duration = Infinity for live streams (per the Media Session spec), and Math.round would
        // saturate it to Long.MAX_VALUE, overflowing the microsecond conversion below into a
        // negative duration. Treat anything non-representable as "no duration" (live/indeterminate),
        // and clamp the position the same way.
        final long durationMs = isRepresentableTime(duration) ? Math.round(duration * 1000.0) : C.TIME_UNSET;
        long positionMs = isRepresentableTime(position) ? Math.round(position * 1000.0) : 0L;
        if (durationMs != C.TIME_UNSET) {
            positionMs = Math.min(positionMs, durationMs);
        }

        // The playlist always contains the current item. Placeholder items are added before and
        // after it when "previoustrack"/"nexttrack" handlers are registered: SimpleBasePlayer
        // only routes seekToNext()/seekToPrevious() to handleSeek() if such neighboring items
        // exist, so without them the system's previous/next buttons would be inoperable.
        final List<MediaItemData> playlist = new ArrayList<>(3);
        if (supportedActions.contains("previoustrack")) {
            playlist.add(new MediaItemData.Builder(PREVIOUS_ITEM_UID).build());
        }
        final int currentItemIndex = playlist.size();
        playlist.add(
            new MediaItemData.Builder(CURRENT_ITEM_UID)
                .setMediaItem(new MediaItem.Builder().setMediaId(MEDIA_ID).build())
                .setMediaMetadata(mediaMetadata)
                .setDurationUs(durationMs != C.TIME_UNSET ? durationMs * 1000 : C.TIME_UNSET)
                .setIsSeekable(supportedActions.contains("seekto"))
                .build()
        );
        if (supportedActions.contains("nexttrack")) {
            playlist.add(new MediaItemData.Builder(NEXT_ITEM_UID).build());
        }

        // NaN/Infinity playback rates fall back to 1x: Media3's PlaybackParameters accepts any
        // speed > 0, so an Infinity would pass its check and blow up position extrapolation.
        final float speed = (Double.isFinite(playbackRate) && playbackRate > 0.0) ? (float) playbackRate : 1.0f;
        final PositionSupplier positionSupplier = playing
            ? PositionSupplier.getExtrapolating(positionMs, speed)
            : PositionSupplier.getConstant(positionMs);

        return new State.Builder()
            .setAvailableCommands(commands.build())
            .setPlaybackState(media3PlaybackState)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentItemIndex)
            .setContentPositionMs(positionSupplier)
            .setSeekForwardIncrementMs(SEEK_FORWARD_INCREMENT_MS)
            .setSeekBackIncrementMs(SEEK_BACK_INCREMENT_MS)
            .setPlaybackParameters(new PlaybackParameters(speed))
            .build();
    }

    /**
     * Largest time (seconds) that survives the seconds → milliseconds → microseconds conversions
     * into Media3's timeline without overflowing a {@code long}. Values at or above it (notably
     * {@code Infinity}, which JS uses for live streams) are treated as unset/zero by
     * {@link #isRepresentableTime}.
     */
    static final double MAX_REPRESENTABLE_TIME_SECONDS = Long.MAX_VALUE / 1_000_000.0;

    /** Whether {@code seconds} is a finite, positive time that fits Media3's microsecond timeline. */
    static boolean isRepresentableTime(double seconds) {
        return seconds > 0.0 && seconds < MAX_REPRESENTABLE_TIME_SECONDS;
    }

    private MediaMetadata buildMediaMetadata() {
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album);
        if (artworkData != null) {
            builder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
        }
        return builder.build();
    }

    private int getCurrentItemIndexInternal() {
        return supportedActions.contains("previoustrack") ? 1 : 0;
    }

    private void notifyAction(String action, @Nullable Double seekTime, @Nullable Double seekOffset) {
        if (actionCallback != null) {
            actionCallback.onAction(action, seekTime, seekOffset);
        } else {
            Log.d(TAG, "Dropping action " + action + " because no action callback is connected");
        }
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        // Update optimistically so the system play/pause button does not snap back while the
        // command makes its round trip through the WebView. JavaScript confirms (or corrects)
        // the state with the next setPlaybackState() call.
        if (!playbackState.equals("none")) {
            playbackState = playWhenReady ? "playing" : "paused";
        }
        notifyAction(playWhenReady ? "play" : "pause", null, null);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, @Player.Command int seekCommand) {
        switch (seekCommand) {
            case Player.COMMAND_SEEK_TO_NEXT:
            case Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM:
                notifyAction("nexttrack", null, null);
                break;
            case Player.COMMAND_SEEK_TO_PREVIOUS:
            case Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM:
                notifyAction("previoustrack", null, null);
                break;
            case Player.COMMAND_SEEK_FORWARD:
                notifyAction("seekforward", null, getSeekForwardIncrement() / 1000.0);
                break;
            case Player.COMMAND_SEEK_BACK:
                notifyAction("seekbackward", null, getSeekBackIncrement() / 1000.0);
                break;
            case Player.COMMAND_SEEK_TO_MEDIA_ITEM:
                if (mediaItemIndex != getCurrentItemIndexInternal()) {
                    notifyAction(mediaItemIndex > getCurrentItemIndexInternal() ? "nexttrack" : "previoustrack", null, null);
                    break;
                }
                // fall through: seek inside the current item
            case Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM:
            case Player.COMMAND_SEEK_TO_DEFAULT_POSITION:
                final double seekTime = positionMs == C.TIME_UNSET ? 0.0 : positionMs / 1000.0;
                // Optimistic update to keep the system seek bar from snapping back until
                // JavaScript reports the new position via setPositionState().
                position = seekTime;
                notifyAction("seekto", seekTime, null);
                break;
            default:
                Log.d(TAG, "Unhandled seek command " + seekCommand);
                break;
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        // Intentionally no optimistic state change: JavaScript reacts to the "stop" action by
        // setting the playback state to "none", which is what actually tears the session down.
        notifyAction("stop", null, null);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        // Nothing to release; without this override SimpleBasePlayer throws when the session
        // service is destroyed.
        return Futures.immediateVoidFuture();
    }

    /** Returns the actions for which handlers are currently registered (for testing). */
    Set<String> getSupportedActions() {
        return Collections.unmodifiableSet(supportedActions);
    }
}
