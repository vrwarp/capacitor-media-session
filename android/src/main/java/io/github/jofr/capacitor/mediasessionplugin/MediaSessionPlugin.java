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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.jofr.capacitor.mediasessionplugin.CustomActions.CustomActionSpec;

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
    /**
     * Encoded cover art handed to Media3. Written on the main looper (see the artwork-fetch flow in
     * {@link #setMetadata}) and read by the bridge thread in {@link #updateServiceState}, hence
     * {@code volatile}.
     */
    private volatile byte[] artworkData = null;
    private String playbackState = "none";
    private double duration = 0.0;
    private double position = 0.0;
    private double playbackRate = 1.0;
    /**
     * Registered action handlers (action -> kept-alive {@code RETURN_CALLBACK} {@link PluginCall}).
     * MAIN-LOOPER-CONFINED: every read and write happens on the main looper. Writers (registration /
     * removal in {@link #applyActionHandler}), readers (the {@code keySet()} snapshot in
     * {@link #pushPlayerState}, {@link #hasActionHandler}, {@link #actionCallback}) and the
     * PluginCall resolve/release/setKeepAlive lifecycle all run there, so a plain {@link HashMap} is
     * safe without further synchronization. The bridge-thread {@link #setActionHandler} only posts to
     * {@link #applyActionHandler}.
     */
    private final Map<String, PluginCall> actionHandlers = new HashMap<>();

    /**
     * Custom-action button specs (id -> label + resolved icon) for actions that are not standard
     * Media Session actions. Insertion-ordered so the published custom layout is deterministic.
     * Kept in parallel with {@link #actionHandlers} and maintained on register/re-register/remove.
     * MAIN-LOOPER-CONFINED like {@link #actionHandlers}.
     */
    private final Map<String, CustomActionSpec> customActions = new LinkedHashMap<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Set once in {@link #handleOnDestroy}. Read on the main looper by late artwork-delivery and
     * {@code setMetadata} runnables to drop work that would touch a torn-down player/state.
     * {@code volatile} because it is written from the destroy path and read from main-looper
     * runnables.
     */
    private volatile boolean destroyed = false;

    /**
     * Loads encoded cover art bytes for a given artwork {@code src}. Indirection over
     * {@link #urlToArtworkData} so tests can inject a deterministic fetcher (no real network) — not
     * a public/plugin API.
     */
    interface ArtworkFetcher {
        byte[] fetch(String src) throws IOException;
    }

    private ArtworkFetcher artworkFetcher = this::urlToArtworkData;

    /**
     * Single-threaded, daemon executor that runs the blocking artwork fetch OFF the Capacitor
     * bridge thread (every {@code @PluginMethod} runs on one shared background HandlerThread, so a
     * synchronous {@code HttpURLConnection} in {@code setMetadata} would block every other plugin
     * call). The executor only touches a captured local {@code src} and returns bytes by value; all
     * shared state ({@link #artworkData}, {@link #artworkGeneration}) is mutated back on the main
     * looper.
     */
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-session-artwork");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Monotonic token identifying the most recent artwork request. MAIN-THREAD-CONFINED: bumped and
     * compared only on the main looper so a slow/older fetch result is discarded when a newer
     * {@code setMetadata} artwork request has superseded it.
     */
    private long artworkGeneration = 0;

    /** Test seam: replace the network-backed artwork fetcher. Package-private, NOT a plugin API. */
    void setArtworkFetcher(ArtworkFetcher fetcher) {
        this.artworkFetcher = fetcher;
    }

    /**
     * Test hook: block until any in-flight artwork fetch on {@link #artworkExecutor} has run (up to
     * {@code timeoutMs}). Because the executor is single-threaded, submitting a barrier task and
     * awaiting it guarantees all previously-submitted fetches have completed. The result-delivery
     * runnable they post back to the main looper must still be drained separately via
     * {@code idleMainLooper()}. Package-private, test-only.
     */
    boolean awaitArtworkIdle(long timeoutMs) {
        try {
            artworkExecutor.submit(() -> {}).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private MediaSessionService service = null;
    private boolean serviceBindingRequested = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Log.i(TAG, "onServiceConnected: binding proxy player action callback and pushing state");
            MediaSessionService.LocalBinder binder = (MediaSessionService.LocalBinder) iBinder;
            service = binder.getService();
            service.setPlugin(MediaSessionPlugin.this);
            WebViewProxyPlayer player = service.getPlayer();
            if (player != null) {
                player.setActionCallback(MediaSessionPlugin.this::onPlayerAction);
            }
            updateServiceState();
            // Replay any custom actions registered before the service bound.
            updateCustomLayout();
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
     * Schedules a push of the current metadata, playback and position state to the proxy player on
     * the main thread (the player must only be accessed from its application looper). Just hops to
     * {@link #pushPlayerState()}; the {@code actionHandlers} {@code keySet()} snapshot now happens
     * there so the handler map is read on the main looper too.
     */
    private void updateServiceState() {
        mainHandler.post(this::pushPlayerState);
    }

    /**
     * Pushes the current metadata, playback and position state to the proxy player. MUST run on the
     * main looper: it reads {@link #actionHandlers} (main-looper-confined) to build the supported
     * standard-action set and touches the proxy player, which is bound to the application looper.
     */
    private void pushPlayerState() {
        final String playbackState = this.playbackState;
        final String title = this.title;
        final String artist = this.artist;
        final String album = this.album;
        final byte[] artworkData = this.artworkData;
        final double duration = this.duration;
        final double position = this.position;
        final double playbackRate = this.playbackRate;
        // Only the standard actions map to Player.Commands; custom actions are surfaced separately
        // as session custom-layout buttons, so they must not reach the proxy player's command switch.
        // Read on the main looper (see actionHandlers confinement).
        final Set<String> supportedActions = new HashSet<>();
        for (String action : actionHandlers.keySet()) {
            if (CustomActions.isStandard(action)) {
                supportedActions.add(action);
            }
        }

        MediaSessionService service = this.service;
        WebViewProxyPlayer player = service != null ? service.getPlayer() : null;
        if (player == null) {
            Log.w(TAG, "pushPlayerState: service not bound yet — dropping state update (playbackState="
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

    /**
     * Sentinel max-edge for the {@code "any"} {@code sizes} token (e.g. vector artwork). The selector
     * substitutes {@link #MAX_ARTWORK_DIMENSION} for it so an {@code "any"} entry ties an ideal raster
     * of the target size rather than always winning as "largest".
     */
    static final int ANY_EDGE = Integer.MAX_VALUE;

    /**
     * Parses a Media Session {@code sizes} string (space-separated {@code WxH} tokens, case-insensitive
     * {@code x}) into the entry's max edge: the largest {@code max(W,H)} across its tokens. The
     * special token {@code "any"} yields {@link #ANY_EDGE}. Missing/empty/unparseable input returns
     * {@code 0}. Pure (unit-tested via {@code ArtworkSelectionTest}).
     */
    static int parseMaxEdge(String sizes) {
        if (sizes == null) {
            return 0;
        }
        int best = 0;
        boolean any = false;
        for (String token : sizes.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("any")) {
                any = true;
                continue;
            }
            int xIndex = token.indexOf('x');
            if (xIndex < 0) {
                xIndex = token.indexOf('X');
            }
            if (xIndex <= 0 || xIndex >= token.length() - 1) {
                continue;
            }
            try {
                int w = Integer.parseInt(token.substring(0, xIndex));
                int h = Integer.parseInt(token.substring(xIndex + 1));
                best = Math.max(best, Math.max(w, h));
            } catch (NumberFormatException e) {
                // ignore unparseable token
            }
        }
        if (best == 0 && any) {
            return ANY_EDGE;
        }
        return best;
    }

    /**
     * Picks the single most appropriate artwork {@code src} for a {@code targetEdge} (in px), so only
     * ONE image is fetched per {@code setMetadata} (avoiding redundant downloads). Entries with a
     * null/empty {@code src} are skipped. Preference: the entry whose max edge is {@code >= targetEdge}
     * and SMALLEST among those; otherwise the LARGEST available (closest from below); when all sizes
     * are unknown ({@code 0}) the LAST usable entry wins (preserves the historical last-wins
     * behaviour); ties resolve to the LAST such entry. Returns {@code null} when nothing is usable.
     * Pure (unit-tested via {@code ArtworkSelectionTest}).
     */
    static String selectArtworkSrc(List<JSONObject> artwork, int targetEdge) {
        if (artwork == null) {
            return null;
        }
        String bestAtOrAbove = null;
        int bestAtOrAboveEdge = Integer.MAX_VALUE;
        String bestBelow = null;
        int bestBelowEdge = -1;
        for (JSONObject entry : artwork) {
            if (entry == null) {
                continue;
            }
            String src = entry.optString("src", null);
            if (src == null || src.isEmpty()) {
                continue;
            }
            int edge = parseMaxEdge(entry.optString("sizes", null));
            if (edge == ANY_EDGE) {
                // "any" ties an ideal raster of the target size.
                edge = targetEdge;
            }
            if (edge >= targetEdge) {
                // "<=" so a later tie at the same edge wins (last-wins on ties).
                if (edge <= bestAtOrAboveEdge) {
                    bestAtOrAboveEdge = edge;
                    bestAtOrAbove = src;
                }
            } else {
                // ">=" so a later tie (including the all-unknown 0 case) wins (last-wins on ties).
                if (edge >= bestBelowEdge) {
                    bestBelowEdge = edge;
                    bestBelow = src;
                }
            }
        }
        if (bestAtOrAbove != null) {
            return bestAtOrAbove;
        }
        return bestBelow;
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
        // Text fields are applied synchronously on the bridge thread (current-field defaults).
        title = call.getString("title", title);
        artist = call.getString("artist", artist);
        album = call.getString("album", album);

        final JSArray artworkArray = call.getArray("artwork");
        if (artworkArray == null) {
            // Artwork key absent: leave any previous cover untouched, push the text update, done.
            updateServiceState();
            call.resolve();
            return;
        }

        // Artwork key present: select a single src (off-thread fetch) on the main looper so the
        // artworkData/artworkGeneration single-writer invariant holds. Resolve immediately on the
        // bridge thread — the promise must NOT wait for the (possibly slow) network fetch.
        final List<JSONObject> artworkList = artworkArray.toList();
        mainHandler.post(() -> {
            if (destroyed) {
                // Plugin torn down between posting and running: do not touch the (released) player/state.
                return;
            }
            final long generation = ++artworkGeneration;
            final String src = selectArtworkSrc(artworkList, MAX_ARTWORK_DIMENSION);
            // Reflect the text update immediately (artwork may change again below / shortly after).
            // Already on main, so push inline rather than re-posting.
            pushPlayerState();
            if (src == null) {
                // Array present but nothing usable: clear any previous cover (stale-clearing rule).
                artworkData = null;
                pushPlayerState();
                return;
            }
            artworkExecutor.submit(() -> {
                byte[] data;
                try {
                    data = artworkFetcher.fetch(src);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "Could not load artwork from " + src, e);
                    data = null;
                }
                final byte[] result = data;
                mainHandler.post(() -> {
                    if (destroyed) {
                        // Plugin torn down while the fetch was in flight; drop the late result so it
                        // never touches a released player/state (R-3 destroyed guard).
                        return;
                    }
                    if (generation != artworkGeneration) {
                        // A newer artwork request superseded this one; discard the stale result.
                        return;
                    }
                    // Assign unconditionally: a failed/empty fetch CLEARS the previous cover so the
                    // displayed artwork always reflects the most recently supplied array.
                    artworkData = result;
                    pushPlayerState();
                });
            });
        });

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
        // Omitted fields preserve the previously set values (mirroring setMetadata's text defaulting);
        // pass 0/0/1 explicitly to reset.
        duration = call.getDouble("duration", this.duration);
        position = call.getDouble("position", this.position);
        playbackRate = call.getFloat("playbackRate", (float) this.playbackRate);

        updateServiceState();
        call.resolve();
    }

    /**
     * Read-back getter for the cached playback state. Returns the last value set via
     * {@link #setPlaybackState} (not a live read of the system session).
     *
     * <p>SAME-BRIDGE-THREAD invariant: this runs on the Capacitor bridge thread and reads
     * {@code playbackState}, which is WRITTEN on the same bridge thread by {@link #setPlaybackState}.
     * Because every {@code @PluginMethod} runs on one shared background HandlerThread, the read and
     * write are serialized on that single thread, so no synchronization is needed.
     */
    @PluginMethod
    public void getPlaybackState(PluginCall call) {
        JSObject result = new JSObject();
        result.put("playbackState", playbackState);
        call.resolve(result);
    }

    /**
     * Read-back getter for the cached metadata TEXT fields. Returns the last {@code title}/
     * {@code artist}/{@code album} set via {@link #setMetadata}; {@code artwork} is intentionally
     * OMITTED because only decoded image bytes are cached natively, not the original artwork array.
     *
     * <p>SAME-BRIDGE-THREAD invariant: runs on the Capacitor bridge thread and reads
     * {@code title}/{@code artist}/{@code album}, all WRITTEN on the same bridge thread by
     * {@link #setMetadata}. {@code artworkData} (written on the MAIN looper) is deliberately NOT read
     * here, so this getter stays single-thread-confined and needs no synchronization.
     */
    @PluginMethod
    public void getMetadata(PluginCall call) {
        JSObject result = new JSObject();
        result.put("title", title);
        result.put("artist", artist);
        result.put("album", album);
        call.resolve(result);
    }

    /**
     * Read-back getter for the cached position state. Returns the last {@code duration}/
     * {@code position}/{@code playbackRate} set via {@link #setPositionState}.
     *
     * <p>SAME-BRIDGE-THREAD invariant: runs on the Capacitor bridge thread and reads
     * {@code duration}/{@code position}/{@code playbackRate}, all WRITTEN on the same bridge thread by
     * {@link #setPositionState}; serialized on the single shared HandlerThread, so no synchronization
     * is needed.
     */
    @PluginMethod
    public void getPositionState(PluginCall call) {
        JSObject result = new JSObject();
        result.put("duration", duration);
        result.put("position", position);
        result.put("playbackRate", playbackRate);
        call.resolve(result);
    }

    /**
     * Thin bridge-thread prologue for the {@code RETURN_CALLBACK} action-handler registration. It
     * only validates {@code action} (rejecting on the bridge thread as before) and, for the
     * non-remove case, establishes the {@code setKeepAlive(true)} contract BEFORE the call escapes to
     * the main looper. All handler-map mutation, PluginCall release/resolve and the player/layout
     * publish are deferred to {@link #applyActionHandler} on the main looper so the maps are touched
     * from exactly one thread (see {@link #actionHandlers} confinement). The remove case is resolved
     * inside the runnable, not here.
     */
    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    public void setActionHandler(PluginCall call) {
        final String action = call.getString("action");
        if (action == null || action.isEmpty()) {
            call.reject("action is required");
            return;
        }

        final boolean remove = call.getBoolean("removeHandler", false);
        final String label = call.getString("label");
        final String icon = call.getString("icon");

        if (!remove) {
            // Establish the RETURN_CALLBACK keep-alive contract before the call crosses threads, so
            // the call is not auto-released while the registration runnable is in flight. (Matches the
            // existing setKeepAlive expectation.)
            call.setKeepAlive(true);
        }

        mainHandler.post(() -> applyActionHandler(action, remove, label, icon, call));
    }

    /**
     * Applies an action-handler registration/removal on the MAIN looper: releases any previously
     * stored kept-alive call, updates {@link #actionHandlers} and {@link #customActions}, then
     * publishes the player state and (for custom actions) the session custom layout INLINE — all in a
     * single main-looper turn so registration and publish settle consistently. For the remove case it
     * also resolves {@code call} here (the bridge prologue intentionally did not).
     */
    private void applyActionHandler(String action, boolean remove, String label, String icon, PluginCall call) {
        final boolean custom = CustomActions.isCustom(action);

        // Always release any previously stored kept-alive call for this action before replacing or
        // removing it; otherwise re-registration would leak the old RETURN_CALLBACK PluginCall.
        PluginCall previous = actionHandlers.remove(action);
        if (previous != null && previous.isKeptAlive() && !previous.isReleased()) {
            previous.release(getBridge());
        }

        if (remove) {
            // This call carries no live JS handler (the null callback was translated into a
            // removeHandler flag by the TS wrapper), so resolve it instead of keeping it alive.
            if (custom) {
                customActions.remove(action);
            }
            Log.d(TAG, "applyActionHandler: removed '" + action + "' supportedActions=" + actionHandlers.keySet());
            pushPlayerState();
            updateCustomLayout();
            call.resolve();
            return;
        }

        if (custom) {
            // Re-registering replaces the existing entry; remove first so the LinkedHashMap re-inserts
            // it at the end (toggle), then store the latest label/icon.
            customActions.remove(action);
            final int iconConstant = CustomActions.iconConstant(icon);
            customActions.put(action, new CustomActionSpec(action, label, iconConstant));
        }

        // Keep-alive was already set on the bridge thread in setActionHandler.
        actionHandlers.put(action, call);
        Log.d(TAG, "applyActionHandler: registered '" + action + "' supportedActions=" + actionHandlers.keySet());
        pushPlayerState();
        if (custom) {
            updateCustomLayout();
        }
    }

    /**
     * Pushes the current ordered custom-action specs to the service so it can rebuild the session's
     * custom layout. Snapshots {@link #customActions} on the MAIN looper (its only thread): callers
     * already run on main ({@link #applyActionHandler}, {@code onServiceConnected}), so the snapshot
     * is taken inline before handing it to the service. Guarded like {@link #pushPlayerState}: if the
     * service is not bound yet, the actions are replayed from {@code onServiceConnected}.
     */
    private void updateCustomLayout() {
        final List<CustomActionSpec> specs = new ArrayList<>(customActions.values());
        MediaSessionService service = this.service;
        if (service == null) {
            Log.w(TAG, "updateCustomLayout: service not bound yet — dropping " + specs.size()
                    + " custom action(s) (will replay on connect)");
            return;
        }
        service.updateCustomActions(specs);
    }

    /**
     * Whether a live (non-dangling, non-released) handler is registered for {@code action}. Reads
     * {@link #actionHandlers}; call on the MAIN looper (its confinement thread).
     */
    public boolean hasActionHandler(String action) {
        PluginCall call = actionHandlers.get(action);
        return call != null
                && !call.getCallbackId().equals(PluginCall.CALLBACK_ID_DANGLING)
                && !call.isReleased();
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

    /**
     * Resolves the kept-alive handler for {@code action} with {@code data}. Reads
     * {@link #actionHandlers} and touches the {@link PluginCall}; call on the MAIN looper (its
     * confinement thread). A single {@code get} avoids any TOCTOU between the liveness check and the
     * resolve, and the {@code isReleased()} guard drops taps that arrive after the stored call was
     * released (e.g. handler just removed/re-registered).
     */
    public void actionCallback(String action, JSObject data) {
        PluginCall call = actionHandlers.get(action);
        if (call != null
                && !PluginCall.CALLBACK_ID_DANGLING.equals(call.getCallbackId())
                && !call.isReleased()) {
            data.put("action", action);
            call.resolve(data);
        } else {
            Log.w(TAG, "actionCallback DROPPED: no live handler for '" + action
                    + "' (registration race, released, or never registered) — control will do nothing");
        }

        // ADDITIVE event channel: emit 'action' UNCONDITIONALLY so addListener('action', cb) receives
        // EVERY action (standard + custom) even when no kept-alive setActionHandler handler is
        // registered. This is in ADDITION to the kept-alive handler resolve above; the keep-alive
        // per-tap delivery is unchanged. Build a fresh copy so we do not alias the exact JSObject
        // handed to call.resolve(data) above (Capacitor may consume/serialize it). Already on the main
        // looper (actionCallback's confinement thread / onPlayerAction posts here).
        JSObject event = new JSObject();
        for (java.util.Iterator<String> it = data.keys(); it.hasNext(); ) {
            String key = it.next();
            event.put(key, data.opt(key));
        }
        event.put("action", action);
        notifyListeners("action", event);
    }

    @Override
    protected void handleOnDestroy() {
        // R-3 destroyed guard: flip the flag first so any already-queued main-looper runnable (a
        // pending setMetadata post or a late artwork result-delivery) drops its work instead of
        // touching the released player/state. Then clear pending main-looper callbacks BEFORE
        // shutting down the artwork executor / stopping the service, so nothing re-posts afterwards.
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        artworkExecutor.shutdownNow();
        stopMediaService();
        super.handleOnDestroy();
    }
}
