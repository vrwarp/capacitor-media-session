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
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
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

    /**
     * Hard cap (bytes) on an ENCODED artwork image before it is decoded. Guards the single-thread
     * artwork executor against an unbounded/malicious download (HTTP) or a huge embedded base64
     * payload (data: URI) OOMing the process before {@code BitmapFactory} ever sees it. The decode
     * itself is additionally downsampled (see {@link #computeInSampleSize(int, int, int)}), but this
     * cap bounds the raw buffer that must be held in memory in the first place.
     */
    private static final int MAX_ARTWORK_BYTES = 8 * 1024 * 1024;

    private boolean startServiceOnlyDuringPlayback = true;

    private String title = "";
    private String artist = "";
    private String album = "";
    /**
     * Raw artwork array exactly as supplied to {@link #setMetadata} (a {@link JSArray}, which extends
     * {@code org.json.JSONArray}). BRIDGE-THREAD-CONFINED: written in {@link #setMetadata} and read in
     * {@link #getMetadata}, both running on the Capacitor bridge HandlerThread, so no synchronization
     * is needed. This is NOT the main-written decoded {@link #artworkData}; it mirrors what the web
     * {@code getMetadata} getter returns (the original array, not a re-encoding of decoded bytes).
     */
    private JSArray artworkMetadata = null;
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

    /**
     * The bound {@link MediaSessionService}, or {@code null} when not bound. MAIN-LOOPER-CONFINED:
     * every read and write happens on the main looper. Writers ({@link #startMediaService} /
     * {@link #stopMediaService}, both of which must be called on main, and
     * {@code onServiceConnected}/{@code onServiceDisconnected}, which Android already delivers on the
     * main looper), and readers ({@link #pushPlayerState}, {@link #updateCustomLayout},
     * {@link #applyPlaybackState}, {@link #handleOnDestroy}) all run there, so the check-then-bind in
     * {@link #applyPlaybackState} is atomic with respect to the connection callbacks without
     * {@code volatile} or further synchronization.
     */
    private MediaSessionService service = null;

    /**
     * Whether a {@code bindService} request is currently outstanding (guards against a duplicate bind
     * and tracks whether {@code unbindService} must be called). MAIN-LOOPER-CONFINED exactly like
     * {@link #service}: mutated only on the main looper by {@link #startMediaService} /
     * {@link #stopMediaService} / {@code onServiceDisconnected}, so no {@code volatile} is needed.
     */
    private boolean serviceBindingRequested = false;

    /**
     * Pending debounced service-teardown runnable, or {@code null} when none is scheduled.
     * MAIN-LOOPER-CONFINED: created/cleared only on the main looper by {@link #scheduleServiceTeardown}
     * / {@link #cancelPendingServiceTeardown} / its own body, and posted via {@link #mainHandler}.
     */
    private Runnable pendingServiceTeardown = null;

    /**
     * Settle window (ms) before a non-playing state actually tears the service down. Absorbs the brief
     * {@code 'none'} that occurs between tracks (and other transient non-playing blips) so the service
     * binding is not churned (unbound/stopped then immediately rebound) when playback resumes within
     * the window.
     */
    private static final long SERVICE_TEARDOWN_DELAY_MS = 750;

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
            // Defer one looper turn so that EVERY mutation of service/serviceBindingRequested happens
            // on the main looper (load() may run on a different thread); startMediaService assumes main.
            mainHandler.post(this::startMediaService);
        }
    }

    /**
     * Binds the {@link MediaSessionService}. MUST be called on the main looper (it reads/writes
     * {@link #service} and {@link #serviceBindingRequested}, which are main-looper-confined).
     */
    private void startMediaService() {
        if (serviceBindingRequested) {
            return;
        }
        serviceBindingRequested = true;
        Log.i(TAG, "startMediaService: bindService(MediaSessionService, BIND_AUTO_CREATE)");
        Intent intent = new Intent(getContext(), MediaSessionService.class);
        getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Unbinds and stops the {@link MediaSessionService}. MUST be called on the main looper (it
     * reads/writes {@link #service} and {@link #serviceBindingRequested}, which are
     * main-looper-confined).
     */
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
     * Pure (unit-tested) {@code BitmapFactory.Options.inSampleSize} computation: the power-of-two
     * subsampling factor used to decode a {@code srcW}x{@code srcH} image down toward {@code maxEdge}
     * without first allocating the full-resolution bitmap.
     *
     * <p>Half-dimension loop: starting from {@code 1}, the factor is doubled while BOTH half-edges
     * ({@code srcH/2/s} and {@code srcW/2/s}) still stay at or above {@code maxEdge}. The result is the
     * largest power of two such that the subsampled image is not smaller than {@code maxEdge} on either
     * edge (the final exact downscale to {@code maxEdge} is done afterwards by {@code scaleBitmap}).
     * Always returns a power of two {@code >= 1}; degenerate {@code srcW}/{@code srcH <= 0} returns
     * {@code 1}.
     */
    static int computeInSampleSize(int srcW, int srcH, int maxEdge) {
        int s = 1;
        while (srcW > 0 && srcH > 0 && (srcH / 2 / s) >= maxEdge && (srcW / 2 / s) >= maxEdge) {
            s *= 2;
        }
        return s;
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

    /**
     * Decodes a {@code data:} URI (RFC 2397) into its RAW payload bytes (NOT a bitmap). Returns
     * {@code null} for any input that is not a well-formed {@code data:} URI, or whose body fails to
     * decode. Pure / package-private (unit-tested via {@code ArtworkDataUriTest}).
     *
     * <p>The {@code base64} flag is detected by TOKEN match: the metadata segment (between {@code data:}
     * and the first comma) is split on {@code ;} and a segment must exactly {@code equals} {@code base64}
     * — so e.g. {@code ;charset=base64x} is NOT treated as base64. A base64 body is decoded with
     * {@code Base64.DEFAULT} (standard alphabet, per RFC 2397); a non-base64 body is percent-decoded as
     * UTF-8. Any decode failure yields {@code null} (no exception escapes).
     */
    static byte[] decodeDataUri(String url) {
        if (url == null || !url.startsWith("data:")) {
            return null;
        }
        String remainder = url.substring("data:".length());
        int comma = remainder.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String meta = remainder.substring(0, comma);
        String body = remainder.substring(comma + 1);

        boolean base64 = false;
        for (String segment : meta.toLowerCase().split(";")) {
            if (segment.equals("base64")) {
                base64 = true;
                break;
            }
        }

        if (base64) {
            // A data: URI's ;base64 payload is standard base64 per RFC 2397/4648; a body the standard
            // alphabet rejects is malformed and yields null (the cover is then cleared by the caller).
            try {
                return Base64.decode(body, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "decodeDataUri: base64 body rejected", e);
                return null;
            }
        }

        try {
            return URLDecoder.decode(body, "UTF-8").getBytes("UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            Log.w(TAG, "decodeDataUri: could not percent-decode data: body", e);
            return null;
        }
    }

    /**
     * Dispatches an artwork {@code src} URL by SCHEME and returns encoded cover-art bytes (or
     * {@code null}). {@code data:} URIs are decoded in-process (base64 or percent-encoded);
     * {@code http(s)://} is fetched over the network (hardened/size-capped, see
     * {@link #httpToArtworkData(String)}); {@code blob:} remains unsupported; anything else is dropped.
     */
    private byte[] urlToArtworkData(String url) throws IOException {
        if (url == null) {
            return null;
        }
        if (url.startsWith("data:")) {
            byte[] bytes = decodeDataUri(url);
            if (bytes == null) {
                return null;
            }
            return bytesToArtworkData(bytes, bytes.length);
        }
        if (url.startsWith("blob:")) {
            Log.i(TAG, "Converting Blob URLs to artwork is not yet supported");
            return null;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return httpToArtworkData(url);
        }
        return null;
    }

    /**
     * Fetches an {@code http(s)://} artwork URL into encoded cover-art bytes. Hardened: rejects a
     * non-200 response, and reads at most {@link #MAX_ARTWORK_BYTES} of the body (aborting to
     * {@code null} if the stream exceeds the cap) before a single downsampled decode. The connection
     * is always {@code disconnect()}ed. Runs on the artwork executor (off the bridge thread).
     */
    private byte[] httpToArtworkData(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) (new URL(url)).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setDoInput(true);
        try {
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "httpToArtworkData: non-200 response (" + connection.getResponseCode()
                        + ") for " + url);
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream inputStream = connection.getInputStream()) {
                byte[] chunk = new byte[16 * 1024];
                int total = 0;
                int read;
                while ((read = inputStream.read(chunk)) != -1) {
                    total += read;
                    if (total > MAX_ARTWORK_BYTES) {
                        Log.w(TAG, "httpToArtworkData: artwork exceeds " + MAX_ARTWORK_BYTES
                                + " bytes — aborting fetch of " + url);
                        return null;
                    }
                    buffer.write(chunk, 0, read);
                }
            }
            byte[] bytes = buffer.toByteArray();
            return bytesToArtworkData(bytes, bytes.length);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Two-pass decode of ENCODED image bytes into scaled cover-art bytes, shared by the {@code data:}
     * and {@code http(s)://} branches (a huge embedded base64 PNG is the same OOM risk as a huge
     * download). Pass 1 reads only the bounds ({@code inJustDecodeBounds}) to pick a power-of-two
     * {@code inSampleSize} (see {@link #computeInSampleSize(int, int, int)}); pass 2 decodes the
     * subsampled bitmap, which {@code bitmapToArtworkData}/{@code scaleBitmap} then finishes scaling to
     * exactly {@link #MAX_ARTWORK_DIMENSION}. Returns {@code null} if the bytes do not decode.
     */
    private byte[] bytesToArtworkData(byte[] buf, int len) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(buf, 0, len, options);
        options.inSampleSize = computeInSampleSize(options.outWidth, options.outHeight, MAX_ARTWORK_DIMENSION);
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(buf, 0, len, options);
        return bitmapToArtworkData(bitmap);
    }

    @PluginMethod
    public void setMetadata(PluginCall call) throws JSONException {
        // Text fields are applied synchronously on the bridge thread (current-field defaults).
        title = call.getString("title", title);
        artist = call.getString("artist", artist);
        album = call.getString("album", album);

        final JSArray artworkArray = call.getArray("artwork");
        if (artworkArray == null) {
            // Artwork key absent: leave any previous cover (and the cached raw array) untouched, push
            // the text update, done.
            updateServiceState();
            call.resolve();
            return;
        }

        // Artwork key present (incl. an empty array): cache the raw array verbatim on the bridge
        // thread (so getMetadata can return it) BEFORE posting the fetch. The absent-key case above
        // preserves the previous array; this present-key case overwrites it.
        this.artworkMetadata = artworkArray;

        // Select a single src (off-thread fetch) on the main looper so the
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
                // No usable src to attempt: report the load outcome with no src.
                notifyArtworkLoad(false, null);
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
                        // never touches a released player/state (R-3 destroyed guard). Emit nothing.
                        return;
                    }
                    if (generation != artworkGeneration) {
                        // A newer artwork request superseded this one; discard the stale result and
                        // emit nothing (the newer request emits its own outcome).
                        return;
                    }
                    // Assign unconditionally: a failed/empty fetch CLEARS the previous cover so the
                    // displayed artwork always reflects the most recently supplied array.
                    artworkData = result;
                    pushPlayerState();
                    // Report the load outcome for this (current, non-destroyed) request: loaded when
                    // the fetch produced bytes, false when it failed/returned null (cover cleared).
                    notifyArtworkLoad(result != null, src);
                });
            });
        });

        call.resolve();
    }

    /**
     * Emits the {@code artworkload} event reporting the OUTCOME of a {@link #setMetadata} artwork
     * update (see the TS {@code addListener('artworkload', ...)} overload). {@code loaded} is
     * {@code true} when an image was loaded as the displayed cover, {@code false} when the selected
     * image failed to fetch/decode (cover cleared) or the supplied array had no usable {@code src}.
     * The {@code src} is included only when non-null (the no-usable-src case omits it). Runs on the
     * main looper (its only callers are the {@code setMetadata} artwork runnables, which are already
     * on main); event delivery via {@link #notifyListeners} is itself thread-safe.
     */
    private void notifyArtworkLoad(boolean loaded, String src) {
        JSObject event = new JSObject();
        event.put("loaded", loaded);
        if (src != null) {
            event.put("src", src);
        }
        notifyListeners("artworkload", event);
    }

    @PluginMethod
    public void setPlaybackState(PluginCall call) {
        // playbackState is written on the bridge thread (read back by getPlaybackState there too).
        playbackState = call.getString("playbackState", playbackState);

        // Capture the bind decision input on the bridge thread, then hop to the main looper so the
        // service/serviceBindingRequested mutation stays main-looper-confined. resolve() does not
        // depend on the bind outcome, so it happens immediately on the bridge thread (the setMetadata
        // pattern).
        final boolean playback = playbackState.equals("playing") || playbackState.equals("paused");
        mainHandler.post(() -> applyPlaybackState(playback));
        call.resolve();
    }

    /**
     * Applies the bind/teardown decision for a playback-state change on the MAIN looper (so the
     * service/serviceBindingRequested mutation is main-looper-confined). A {@code playing}/{@code paused}
     * state ensures the service is bound and cancels any pending teardown; a non-playing state in
     * during-playback-only mode schedules a debounced teardown; in {@code always} mode it just pushes
     * the (idle) state and keeps the service.
     */
    private void applyPlaybackState(boolean playback) {
        if (destroyed) {
            return;
        }
        if (playback) {
            // A playing/paused state cancels any pending teardown BEFORE the service==null check, so a
            // none->playing transition within the settle window keeps the existing binding (never
            // tears it down / rebinds).
            cancelPendingServiceTeardown();
            if (service == null) {
                startMediaService();
            }
            // Already on main, so push inline (one deterministic looper turn). When the service is not
            // bound yet pushPlayerState early-returns (harmless/uniform); onServiceConnected pushes the
            // state once the binding completes, as today.
            pushPlayerState();
        } else if (startServiceOnlyDuringPlayback) {
            // During-playback-only mode: debounce the teardown so a brief between-tracks 'none' that is
            // quickly followed by 'playing' does not churn the binding.
            scheduleServiceTeardown();
        } else {
            // 'always' mode: keep the service, just push the (idle) state. Never schedules teardown.
            pushPlayerState();
        }
    }

    /**
     * Schedules a debounced service teardown on the MAIN looper. The FIRST non-playing state starts
     * the clock; a subsequent non-playing state while a teardown is already pending KEEPS the existing
     * timer (does not reset it), so repeated {@code 'none'} events cannot starve the teardown
     * indefinitely. Call on the main looper.
     */
    private void scheduleServiceTeardown() {
        if (pendingServiceTeardown != null) {
            // Keep the existing pending stop; do not reset the timer (prevents starvation).
            return;
        }
        final Runnable runnable = () -> {
            pendingServiceTeardown = null;
            stopMediaService();
        };
        pendingServiceTeardown = runnable;
        mainHandler.postDelayed(runnable, SERVICE_TEARDOWN_DELAY_MS);
    }

    /**
     * Cancels a pending debounced service teardown, if any. No-op when nothing is pending. Call on the
     * main looper.
     */
    private void cancelPendingServiceTeardown() {
        if (pendingServiceTeardown != null) {
            mainHandler.removeCallbacks(pendingServiceTeardown);
            pendingServiceTeardown = null;
        }
    }

    @PluginMethod
    public void setPositionState(PluginCall call) {
        // Omitted fields preserve the previously set values (mirroring setMetadata's text defaulting);
        // pass 0/0/1 explicitly to reset.
        duration = call.getDouble("duration", this.duration);
        position = call.getDouble("position", this.position);
        playbackRate = call.getDouble("playbackRate", this.playbackRate);

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
     * Read-back getter for the cached metadata. Returns the last {@code title}/{@code artist}/
     * {@code album} set via {@link #setMetadata}, plus the original {@code artwork} array returned
     * VERBATIM from the bridge-thread cache (the array as supplied to {@code setMetadata}, NOT a
     * re-encoding of the decoded image bytes). The {@code artwork} key is omitted only when no
     * artwork has ever been set (the cache is still {@code null}).
     *
     * <p>SAME-BRIDGE-THREAD invariant: runs on the Capacitor bridge thread and reads
     * {@code title}/{@code artist}/{@code album} and {@link #artworkMetadata}, all WRITTEN on the same
     * bridge thread by {@link #setMetadata}. The MAIN-written decoded {@link #artworkData} is
     * deliberately NOT read here, so this getter stays single-thread-confined and needs no
     * synchronization.
     */
    @PluginMethod
    public void getMetadata(PluginCall call) {
        JSObject result = new JSObject();
        result.put("title", title);
        result.put("artist", artist);
        result.put("album", album);
        if (artworkMetadata != null) {
            result.put("artwork", artworkMetadata);
        }
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
        final String iconUri = call.getString("iconUri");
        final boolean enabled = call.getBoolean("enabled", true);

        if (!remove) {
            // Establish the RETURN_CALLBACK keep-alive contract before the call crosses threads, so
            // the call is not auto-released while the registration runnable is in flight. (Matches the
            // existing setKeepAlive expectation.)
            call.setKeepAlive(true);
        }

        mainHandler.post(() -> applyActionHandler(action, remove, label, icon, iconUri, enabled, call));
    }

    /**
     * Applies an action-handler registration/removal on the MAIN looper: releases any previously
     * stored kept-alive call, updates {@link #actionHandlers} and {@link #customActions}, then
     * publishes the player state and (for custom actions) the session custom layout INLINE — all in a
     * single main-looper turn so registration and publish settle consistently. For the remove case it
     * also resolves {@code call} here (the bridge prologue intentionally did not).
     */
    private void applyActionHandler(String action, boolean remove, String label, String icon, String iconUri, boolean enabled, PluginCall call) {
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
            customActions.put(action, new CustomActionSpec(action, label, iconConstant, iconUri, enabled));
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
        // Defensive: removeCallbacksAndMessages(null) already dropped the posted teardown, but clear
        // the field too so nothing references a stale runnable. Teardown is never deferred on destroy.
        cancelPendingServiceTeardown();
        artworkExecutor.shutdownNow();
        stopMediaService();
        super.handleOnDestroy();
    }
}
