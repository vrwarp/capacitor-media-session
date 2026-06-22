package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import androidx.test.core.app.ApplicationProvider;

import com.getcapacitor.Bridge;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Functional tests for the Capacitor plugin: plugin calls must be reflected in the Media3 proxy
 * player and player commands must resolve the registered action handler calls.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MediaSessionPluginTest {
    private MediaSessionPlugin plugin;
    private ServiceController<MediaSessionService> serviceController;
    private MediaSessionService service;
    private WebViewProxyPlayer player;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        plugin = new MediaSessionPlugin();
        Bridge bridge = mock(Bridge.class);
        when(bridge.getContext()).thenReturn(context);
        plugin.setBridge(bridge);

        serviceController = Robolectric.buildService(MediaSessionService.class).create();
        service = serviceController.get();
        player = service.getPlayer();

        // Register the real local binder with the ShadowApplication so a real bindService() (e.g. a
        // rebind after a debounced teardown in the R-2 tests) drives onServiceConnected with the live
        // binder instead of Robolectric's default null binder.
        ComponentName serviceComponent = new ComponentName(context, MediaSessionService.class);
        IBinder binder = service.onBind(new Intent(context, MediaSessionService.class));
        ShadowApplication.getInstance().setComponentNameAndServiceForBindService(serviceComponent, binder);

        // Connect the plugin to the service the same way Android would after bindService():
        // through the local binder handed out for intents without an action.
        ServiceConnection connection = getServiceConnection();
        connection.onServiceConnected(serviceComponent, binder);
        idleMainLooper();
    }

    @After
    public void tearDown() {
        if (serviceController != null) {
            serviceController.destroy();
            serviceController = null;
        }
    }

    private ServiceConnection getServiceConnection() throws Exception {
        Field field = MediaSessionPlugin.class.getDeclaredField("serviceConnection");
        field.setAccessible(true);
        return (ServiceConnection) field.get(plugin);
    }

    private Object getPluginField(String name) throws Exception {
        Field field = MediaSessionPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(plugin);
    }

    private void setPluginField(String name, Object value) throws Exception {
        Field field = MediaSessionPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    /** Reads the private static {@code SERVICE_TEARDOWN_DELAY_MS} constant via reflection. */
    private static long SERVICE_TEARDOWN_DELAY_MS() {
        try {
            Field field = MediaSessionPlugin.class.getDeclaredField("SERVICE_TEARDOWN_DELAY_MS");
            field.setAccessible(true);
            return field.getLong(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Advances the main looper by {@code ms}, running any {@code postDelayed} callbacks now due. */
    private static void idleMainFor(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS);
    }

    /**
     * Deterministically drains the asynchronous artwork pipeline:
     * <ol>
     *   <li>{@code idleMainLooper()} — run the main-thread runnable {@code setMetadata} posted, which
     *       selects the src and submits the fetch to the executor;</li>
     *   <li>{@code awaitArtworkIdle} — block until that fetch task has finished on the executor;</li>
     *   <li>{@code idleMainLooper()} — run the result-delivery runnable the fetch posted back to the
     *       main looper (assigns {@code artworkData} and pushes it to the player).</li>
     * </ol>
     * No {@code Thread.sleep}; replaces the previous synchronous assumption.
     */
    private void drainArtwork() {
        idleMainLooper();
        assertTrue("artwork executor should drain", plugin.awaitArtworkIdle(5000));
        idleMainLooper();
    }

    private PluginCall mockActionHandlerCall(String action) {
        PluginCall call = mock(PluginCall.class);
        when(call.getString("action")).thenReturn(action);
        when(call.getCallbackId()).thenReturn("callback-" + action);
        when(call.isKeptAlive()).thenReturn(true);
        when(call.isReleased()).thenReturn(false);
        when(call.getBoolean(eq("removeHandler"), any())).thenReturn(false);
        return call;
    }

    /**
     * Mock PluginCall standing in for an {@code addListener('action', cb)} registration: the base
     * {@link com.getcapacitor.Plugin#addListener} reads {@code eventName} and keeps the call alive.
     */
    private PluginCall mockListenerCall(String eventName) {
        PluginCall call = mock(PluginCall.class);
        when(call.getString("eventName")).thenReturn(eventName);
        when(call.getCallbackId()).thenReturn("listener-" + eventName);
        when(call.isKeptAlive()).thenReturn(true);
        when(call.isReleased()).thenReturn(false);
        return call;
    }

    private PluginCall mockRemoveHandlerCall(String action) {
        PluginCall call = mock(PluginCall.class);
        when(call.getString("action")).thenReturn(action);
        when(call.getCallbackId()).thenReturn("remove-" + action);
        when(call.isKeptAlive()).thenReturn(false);
        when(call.isReleased()).thenReturn(false);
        when(call.getBoolean(eq("removeHandler"), any())).thenReturn(true);
        return call;
    }

    private PluginCall mockCustomActionHandlerCall(String action, String label, String icon) {
        PluginCall call = mockActionHandlerCall(action);
        when(call.getString("label")).thenReturn(label);
        when(call.getString("icon")).thenReturn(icon);
        // Production reads getString("iconUri") and getBoolean("enabled", true); keep the defaults
        // (no iconUri, enabled) so the existing custom-action tests are unaffected.
        when(call.getString("iconUri")).thenReturn(null);
        when(call.getBoolean(eq("enabled"), any())).thenReturn(true);
        return call;
    }

    private PluginCall mockCustomActionHandlerCall(String action, String label, String icon, String iconUri, boolean enabled) {
        PluginCall call = mockCustomActionHandlerCall(action, label, icon);
        when(call.getString("iconUri")).thenReturn(iconUri);
        when(call.getBoolean(eq("enabled"), any())).thenReturn(enabled);
        return call;
    }

    private ImmutableList<CommandButton> customLayout() {
        MediaSession session = service.getMediaSession();
        assertNotNull("media session should be available", session);
        return session.getCustomLayout();
    }

    private void registerActionHandlers(String... actions) {
        for (String action : actions) {
            plugin.setActionHandler(mockActionHandlerCall(action));
        }
        idleMainLooper();
    }

    private void setPlaybackState(String state) {
        setPlaybackStateNoIdle(state);
        idleMainLooper();
    }

    /**
     * Drives {@code setPlaybackState} WITHOUT idling the main looper afterwards, so the test can
     * control exactly how far the looper (and the debounced teardown timer) is advanced.
     */
    private void setPlaybackStateNoIdle(String state) {
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("playbackState"), anyString())).thenReturn(state);
        plugin.setPlaybackState(call);
    }

    @Test
    public void setMetadataPropagatesToPlayer() throws JSONException {
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("title"), anyString())).thenReturn("Song Title");
        when(call.getString(eq("artist"), anyString())).thenReturn("Song Artist");
        when(call.getString(eq("album"), anyString())).thenReturn("Song Album");
        when(call.getArray("artwork")).thenReturn(new JSArray());

        plugin.setMetadata(call);
        idleMainLooper();

        MediaMetadata metadata = player.getMediaMetadata();
        assertEquals("Song Title", String.valueOf(metadata.title));
        assertEquals("Song Artist", String.valueOf(metadata.artist));
        assertEquals("Song Album", String.valueOf(metadata.albumTitle));
        verify(call).resolve();
    }

    @Test
    public void setMetadataDecodesBase64Artwork() throws JSONException {
        PluginCall call = mockMetadataCallWithArtwork(createPngDataUrl(64, 64));

        plugin.setMetadata(call);
        drainArtwork();

        byte[] artworkData = player.getMediaMetadata().artworkData;
        assertNotNull(artworkData);
        Bitmap decoded = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.length);
        assertEquals(64, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
        verify(call).resolve();
    }

    @Test
    public void setMetadataScalesDownOversizedArtwork() throws JSONException {
        PluginCall call = mockMetadataCallWithArtwork(createPngDataUrl(1024, 768));

        plugin.setMetadata(call);
        drainArtwork();

        byte[] artworkData = player.getMediaMetadata().artworkData;
        assertNotNull(artworkData);
        Bitmap decoded = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.length);
        assertEquals(512, decoded.getWidth());
        assertEquals(384, decoded.getHeight());
    }

    @Test
    public void setMetadataToleratesInvalidArtwork() throws JSONException {
        PluginCall call = mockMetadataCallWithArtwork("data:image/png;base64,!!!not-base64!!!");

        plugin.setMetadata(call);
        drainArtwork();

        assertNull(player.getMediaMetadata().artworkData);
        assertEquals("Song Title", String.valueOf(player.getMediaMetadata().title));
        verify(call).resolve();
    }

    private PluginCall mockMetadataCallWithArtwork(String src) throws JSONException {
        PluginCall call = mockMetadataTextCall();
        JSArray artworkArray = new JSArray();
        artworkArray.put(new JSObject().put("src", src));
        when(call.getArray("artwork")).thenReturn(artworkArray);
        return call;
    }

    /** Base metadata call stubbing only the text fields; subclasses decide the artwork stub. */
    private PluginCall mockMetadataTextCall() {
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("title"), anyString())).thenReturn("Song Title");
        when(call.getString(eq("artist"), anyString())).thenReturn("Song Artist");
        when(call.getString(eq("album"), anyString())).thenReturn("Song Album");
        return call;
    }

    /** Metadata call with an artwork array of (src, sizes) pairs; sizes may be null. */
    private PluginCall mockMetadataCallWithArtworkEntries(String[][] entries) throws JSONException {
        PluginCall call = mockMetadataTextCall();
        JSArray artworkArray = new JSArray();
        for (String[] e : entries) {
            JSObject obj = new JSObject();
            if (e[0] != null) {
                obj.put("src", e[0]);
            }
            if (e.length > 1 && e[1] != null) {
                obj.put("sizes", e[1]);
            }
            artworkArray.put(obj);
        }
        when(call.getArray("artwork")).thenReturn(artworkArray);
        return call;
    }

    private String createPngDataUrl(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF336699);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        return "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP);
    }

    @Test
    public void setPlaybackStatePlayingPropagatesToPlayer() {
        registerActionHandlers("play", "pause");

        setPlaybackState("playing");

        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertTrue(player.getPlayWhenReady());
    }

    @Test
    public void setPlaybackStatePausedPropagatesToPlayer() {
        registerActionHandlers("play", "pause");

        setPlaybackState("playing");
        setPlaybackState("paused");

        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertFalse(player.getPlayWhenReady());
    }

    @Test
    public void setPlaybackStateNoneStopsServiceDuringPlaybackOnlyMode() throws Exception {
        setPlaybackState("playing");
        assertNotNull(getPluginField("service"));

        setPlaybackState("none");
        // The teardown is now debounced: the service is still bound immediately after 'none' (the
        // settle window has not been advanced yet).
        assertNotNull(getPluginField("service"));

        // Advance past the settle window: the debounced teardown fires and the service is unbound.
        idleMainFor(SERVICE_TEARDOWN_DELAY_MS() + 50);
        assertNull(getPluginField("service"));
    }

    @Test
    public void setPlaybackStateNoneKeepsServiceInAlwaysMode() throws Exception {
        setPluginField("startServiceOnlyDuringPlayback", false);
        setPlaybackState("playing");

        setPlaybackState("none");
        // 'always' mode never schedules a teardown; even after a long advance the service stays bound.
        idleMainFor(1000);

        assertNotNull(getPluginField("service"));
        assertEquals(Player.STATE_IDLE, player.getPlaybackState());
    }

    // --- Service teardown debounce (R-2) ------------------------------------------------------

    @Test
    public void playingNoneThenPlayingWithinWindowKeepsServiceBound() throws Exception {
        registerActionHandlers("play", "pause");

        setPlaybackState("playing");
        Object boundService = getPluginField("service");
        assertNotNull(boundService);

        // A brief 'none' between tracks schedules the debounced teardown but does not run it.
        setPlaybackStateNoIdle("none");
        idleMainFor(100);
        assertNotNull("teardown should not have fired within the window", getPluginField("service"));

        // 'playing' again within the window cancels the pending teardown and keeps the SAME binding.
        setPlaybackStateNoIdle("playing");
        idleMainFor(1000);

        assertSame("service must not be rebound (same instance)", boundService, getPluginField("service"));
        assertNull("no teardown should remain pending", getPluginField("pendingServiceTeardown"));
        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertTrue(player.getPlayWhenReady());
    }

    @Test
    public void settledNoneTearsDownServiceAfterDelay() throws Exception {
        setPlaybackState("playing");
        assertNotNull(getPluginField("service"));

        setPlaybackState("none");
        // Immediately after 'none' the service is still bound (debounced).
        assertNotNull(getPluginField("service"));

        idleMainFor(SERVICE_TEARDOWN_DELAY_MS() + 50);
        assertNull(getPluginField("service"));
    }

    @Test
    public void repeatedNoneDoesNotResetTeardownWindow() throws Exception {
        setPlaybackState("playing");
        assertNotNull(getPluginField("service"));

        // First 'none' starts the clock.
        setPlaybackState("none");
        idleMainFor(400);
        assertNotNull("still within the original window", getPluginField("service"));

        // A second 'none' must NOT reset the timer; cumulative 800 > 750 from the FIRST 'none' tears
        // the service down.
        setPlaybackState("none");
        idleMainFor(400);

        assertNull("teardown timer must not be reset by repeated 'none'", getPluginField("service"));
    }

    @Test
    public void singleBindSingleUnbindNoIllegalArgument() throws Exception {
        // playing -> none(settled teardown) -> playing(rebind) -> none(settled teardown). No exception
        // across the whole sequence; service bound mid-playback, unbound after each settled teardown.
        setPlaybackState("playing");
        assertNotNull(getPluginField("service"));

        setPlaybackState("none");
        idleMainFor(SERVICE_TEARDOWN_DELAY_MS() + 50);
        assertNull(getPluginField("service"));
        assertFalse("binding released after teardown", (Boolean) getPluginField("serviceBindingRequested"));

        // Rebind.
        setPlaybackState("playing");
        assertNotNull(getPluginField("service"));
        assertTrue("rebind requests a fresh binding", (Boolean) getPluginField("serviceBindingRequested"));

        // Settle down again — no IllegalArgumentException from a double unbind, binding released.
        setPlaybackState("none");
        idleMainFor(SERVICE_TEARDOWN_DELAY_MS() + 50);

        assertNull(getPluginField("service"));
        assertFalse((Boolean) getPluginField("serviceBindingRequested"));
        // Corroborate via Robolectric's bound-connection bookkeeping: zero live connections remain
        // after the final teardown (the unbind was honored, not double-applied).
        assertEquals(0, ShadowApplication.getInstance().getBoundServiceConnections().size());
    }

    @Test
    public void alwaysModeNeverSchedulesTeardown() throws Exception {
        setPluginField("startServiceOnlyDuringPlayback", false);

        setPlaybackState("playing");
        assertNotNull(getPluginField("service"));

        setPlaybackState("none");
        idleMainFor(2000);

        assertNotNull("always mode keeps the service", getPluginField("service"));
        assertNull("always mode never schedules a teardown", getPluginField("pendingServiceTeardown"));
    }

    @Test
    public void handleOnDestroyCancelsPendingTeardownAndStopsImmediately() throws Exception {
        setPlaybackState("playing");
        assertNotNull(getPluginField("service"));

        // Schedule a teardown but do NOT advance the window.
        setPlaybackState("none");
        assertNotNull(getPluginField("service"));
        assertNotNull("teardown should be pending", getPluginField("pendingServiceTeardown"));

        // Destroy: service is stopped immediately and the pending teardown is canceled.
        plugin.handleOnDestroy();
        assertNull(getPluginField("service"));
        assertNull(getPluginField("pendingServiceTeardown"));

        // Advancing afterwards must not double-stop / throw (the canceled runnable never runs).
        idleMainFor(2000);
        assertNull(getPluginField("service"));
    }

    @Test
    public void playbackStateBindDecisionRunsOnMainLooper() throws Exception {
        // setUp() wired the service directly; first settle it down so service starts null/unbound.
        setPlaybackState("none");
        idleMainFor(SERVICE_TEARDOWN_DELAY_MS() + 50);
        assertNull(getPluginField("service"));

        // resolve() happens synchronously on the bridge thread; the bind decision is deferred to the
        // main looper, so the service is not bound until the looper is idled.
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("playbackState"), anyString())).thenReturn("playing");
        plugin.setPlaybackState(call);

        verify(call).resolve();
        assertNull("bind decision must not have run yet", getPluginField("service"));

        idleMainLooper();
        assertNotNull("bind decision runs once the main looper is idled", getPluginField("service"));
    }

    @Test
    public void setPositionStatePropagatesToPlayer() {
        PluginCall call = mock(PluginCall.class);
        when(call.getDouble(eq("duration"), anyDouble())).thenReturn(100.0);
        when(call.getDouble(eq("position"), anyDouble())).thenReturn(50.5);
        when(call.getDouble(eq("playbackRate"), anyDouble())).thenReturn(1.5);

        plugin.setPositionState(call);
        idleMainLooper();

        assertEquals(100_000L, player.getDuration());
        assertEquals(50_500L, player.getCurrentPosition());
        assertEquals(1.5f, player.getPlaybackParameters().speed, 0.0001f);
        verify(call).resolve();
    }

    @Test
    public void setActionHandlerKeepsCallAliveAndEnablesCommands() {
        PluginCall playCall = mockActionHandlerCall("play");

        plugin.setActionHandler(playCall);
        idleMainLooper();

        verify(playCall).setKeepAlive(true);
        assertTrue(plugin.hasActionHandler("play"));
        assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE));
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT));
    }

    @Test
    public void reRegisteringActionReleasesPreviousCallAndKeepsNewOneLive() {
        PluginCall previousCall = mockActionHandlerCall("play");
        plugin.setActionHandler(previousCall);
        idleMainLooper();

        PluginCall newCall = mockActionHandlerCall("play");
        plugin.setActionHandler(newCall);
        idleMainLooper();

        // The previously stored kept-alive call must be released so it does not leak.
        verify(previousCall).release(any());
        verify(newCall).setKeepAlive(true);
        // The new call is now the live handler.
        plugin.actionCallback("play");
        verify(newCall).resolve(any(JSObject.class));
        verify(previousCall, never()).resolve(any(JSObject.class));
    }

    @Test
    public void removeHandlerRemovesHandlerDropsCommandAndReleasesStoredCall() {
        PluginCall playCall = mockActionHandlerCall("play");
        plugin.setActionHandler(playCall);
        idleMainLooper();
        assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE));

        PluginCall removeCall = mockRemoveHandlerCall("play");
        plugin.setActionHandler(removeCall);
        idleMainLooper();

        // The stored kept-alive call is released and the handler is gone.
        verify(playCall).release(any());
        assertFalse(plugin.hasActionHandler("play"));
        assertFalse(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE));
        // The removal call carries no live handler, so it resolves rather than being kept alive.
        verify(removeCall).resolve();
        verify(removeCall, never()).setKeepAlive(true);
    }

    @Test
    public void removeHandlerForUnregisteredActionDoesNotThrowAndResolves() {
        PluginCall removeCall = mockRemoveHandlerCall("seekforward");

        plugin.setActionHandler(removeCall);
        idleMainLooper();

        assertFalse(plugin.hasActionHandler("seekforward"));
        verify(removeCall).resolve();
    }

    @Test
    public void setActionHandlerRejectsWhenActionMissing() {
        PluginCall call = mock(PluginCall.class);
        when(call.getString("action")).thenReturn(null);

        plugin.setActionHandler(call);

        verify(call).reject("action is required");
        verify(call, never()).setKeepAlive(true);
    }

    @Test
    public void playerSeekForwardResolvesSeekforwardHandlerWithOffsetInSeconds() throws JSONException {
        PluginCall seekForwardCall = mockActionHandlerCall("seekforward");
        plugin.setActionHandler(seekForwardCall);
        setPlaybackState("playing");

        player.seekForward();

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(seekForwardCall).resolve(captor.capture());
        JSObject data = captor.getValue();
        assertEquals("seekforward", data.getString("action"));
        assertEquals(WebViewProxyPlayer.SEEK_FORWARD_INCREMENT_MS / 1000.0, data.getDouble("seekOffset"), 0.0001);
        assertFalse(data.has("seekTime"));
    }

    @Test
    public void hasActionHandlerIsFalseForDanglingCallback() {
        PluginCall call = mockActionHandlerCall("play");
        when(call.getCallbackId()).thenReturn(PluginCall.CALLBACK_ID_DANGLING);
        plugin.setActionHandler(call);
        idleMainLooper();

        assertFalse(plugin.hasActionHandler("play"));
    }

    @Test
    public void playerPlayCommandResolvesPlayActionHandler() {
        PluginCall playCall = mockActionHandlerCall("play");
        plugin.setActionHandler(playCall);
        registerActionHandlers("pause");
        setPlaybackState("paused");

        player.play();

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(playCall).resolve(captor.capture());
        assertEquals("play", captor.getValue().getString("action"));
    }

    @Test
    public void playerPauseCommandResolvesPauseActionHandler() {
        PluginCall pauseCall = mockActionHandlerCall("pause");
        plugin.setActionHandler(pauseCall);
        registerActionHandlers("play");
        setPlaybackState("playing");

        player.pause();

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(pauseCall).resolve(captor.capture());
        assertEquals("pause", captor.getValue().getString("action"));
    }

    @Test
    public void playerSeekCommandResolvesSeektoActionHandlerWithSeconds() throws JSONException {
        PluginCall seekCall = mockActionHandlerCall("seekto");
        plugin.setActionHandler(seekCall);
        setPlaybackState("playing");

        player.seekTo(42_000L);

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(seekCall).resolve(captor.capture());
        assertEquals("seekto", captor.getValue().getString("action"));
        assertEquals(42.0, captor.getValue().getDouble("seekTime"), 0.0001);
    }

    @Test
    public void playerNextAndPreviousCommandsResolveTrackHandlers() {
        PluginCall nextCall = mockActionHandlerCall("nexttrack");
        PluginCall previousCall = mockActionHandlerCall("previoustrack");
        plugin.setActionHandler(nextCall);
        plugin.setActionHandler(previousCall);
        setPlaybackState("playing");

        player.seekToNext();
        player.seekToPrevious();

        verify(nextCall).resolve(any(JSObject.class));
        verify(previousCall).resolve(any(JSObject.class));
    }

    @Test
    public void playerStopCommandResolvesStopActionHandler() {
        PluginCall stopCall = mockActionHandlerCall("stop");
        plugin.setActionHandler(stopCall);
        setPlaybackState("playing");

        player.stop();

        verify(stopCall).resolve(any(JSObject.class));
    }

    @Test
    public void actionCallbackIgnoresUnregisteredActions() {
        PluginCall playCall = mockActionHandlerCall("play");
        plugin.setActionHandler(playCall);
        idleMainLooper();

        plugin.actionCallback("nexttrack");

        verify(playCall, never()).resolve(any(JSObject.class));
    }

    @Test
    public void actionCallbackAddsActionToData() {
        PluginCall playCall = mockActionHandlerCall("play");
        plugin.setActionHandler(playCall);
        idleMainLooper();

        plugin.actionCallback("play");

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(playCall).resolve(captor.capture());
        assertEquals("play", captor.getValue().getString("action"));
    }

    // --- Async artwork ------------------------------------------------------------------------

    private static final byte[] FIXED_ARTWORK_BYTES = new byte[] { 1, 2, 3, 4, 5 };

    @Test
    public void setMetadataResolvesBeforeArtworkFetchCompletes() throws Exception {
        // The fetch blocks on a latch; resolve() must already have happened by the time we let it run.
        CountDownLatch fetchGate = new CountDownLatch(1);
        AtomicBoolean fetchStarted = new AtomicBoolean(false);
        plugin.setArtworkFetcher(src -> {
            fetchStarted.set(true);
            try {
                fetchGate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FIXED_ARTWORK_BYTES;
        });

        PluginCall call = mockMetadataCallWithArtwork("http://example.com/cover.png");
        plugin.setMetadata(call);
        // resolve() is synchronous on the bridge thread, independent of the fetch.
        verify(call).resolve();
        // The fetch has not delivered bytes yet (still gated).
        idleMainLooper();
        assertNull(player.getMediaMetadata().artworkData);

        // Release the fetch and drain: bytes now land.
        fetchGate.countDown();
        assertTrue(plugin.awaitArtworkIdle(5000));
        idleMainLooper();
        assertTrue(fetchStarted.get());
        assertArrayEquals(FIXED_ARTWORK_BYTES, player.getMediaMetadata().artworkData);
    }

    @Test
    public void setMetadataDeliversArtworkAsynchronously() throws Exception {
        plugin.setArtworkFetcher(src -> FIXED_ARTWORK_BYTES);

        PluginCall call = mockMetadataCallWithArtwork("http://example.com/cover.png");
        plugin.setMetadata(call);
        drainArtwork();

        assertArrayEquals(FIXED_ARTWORK_BYTES, player.getMediaMetadata().artworkData);
        verify(call).resolve();
    }

    @Test
    public void newArtworkArrayThatFailsClearsPreviousCover() throws Exception {
        // First set a valid base64 cover so artworkData != null (real default fetcher).
        PluginCall first = mockMetadataCallWithArtwork(createPngDataUrl(64, 64));
        plugin.setMetadata(first);
        drainArtwork();
        assertNotNull(player.getMediaMetadata().artworkData);

        // Now supply a new artwork array whose fetch fails; the old cover must be CLEARED.
        plugin.setArtworkFetcher(src -> {
            throw new IOException("boom");
        });
        PluginCall second = mock(PluginCall.class);
        when(second.getString(eq("title"), anyString())).thenReturn("New Title");
        when(second.getString(eq("artist"), anyString())).thenReturn("Song Artist");
        when(second.getString(eq("album"), anyString())).thenReturn("Song Album");
        JSArray artworkArray = new JSArray();
        artworkArray.put(new JSObject().put("src", "http://example.com/missing.png"));
        when(second.getArray("artwork")).thenReturn(artworkArray);

        plugin.setMetadata(second);
        drainArtwork();

        assertNull(player.getMediaMetadata().artworkData);
        assertEquals("New Title", String.valueOf(player.getMediaMetadata().title));
    }

    @Test
    public void absentArtworkKeyPreservesPreviousCover() throws Exception {
        PluginCall first = mockMetadataCallWithArtwork(createPngDataUrl(64, 64));
        plugin.setMetadata(first);
        drainArtwork();
        byte[] cover = player.getMediaMetadata().artworkData;
        assertNotNull(cover);

        // A metadata update with NO artwork key (getArray returns null) must leave the cover intact.
        PluginCall textOnly = mock(PluginCall.class);
        when(textOnly.getString(eq("title"), anyString())).thenReturn("Just Text");
        when(textOnly.getString(eq("artist"), anyString())).thenReturn("Song Artist");
        when(textOnly.getString(eq("album"), anyString())).thenReturn("Song Album");
        when(textOnly.getArray("artwork")).thenReturn(null);

        plugin.setMetadata(textOnly);
        drainArtwork();

        assertArrayEquals(cover, player.getMediaMetadata().artworkData);
        assertEquals("Just Text", String.valueOf(player.getMediaMetadata().title));
        verify(textOnly).resolve();
    }

    @Test
    public void emptyArtworkArrayClearsCoverAndDoesNotFetch() throws Exception {
        PluginCall first = mockMetadataCallWithArtwork(createPngDataUrl(64, 64));
        plugin.setMetadata(first);
        drainArtwork();
        assertNotNull(player.getMediaMetadata().artworkData);

        AtomicBoolean fetched = new AtomicBoolean(false);
        plugin.setArtworkFetcher(src -> {
            fetched.set(true);
            return FIXED_ARTWORK_BYTES;
        });
        // Empty array: no usable src -> clear, and the fetcher must never run.
        PluginCall empty = mockMetadataTextCall();
        when(empty.getArray("artwork")).thenReturn(new JSArray());

        plugin.setMetadata(empty);
        drainArtwork();

        assertNull(player.getMediaMetadata().artworkData);
        assertFalse("empty array must not trigger a fetch", fetched.get());
    }

    @Test
    public void staleArtworkResultIsDiscardedByGeneration() throws Exception {
        // Request A blocks; request B (newer) returns bytes. The final artwork must be B's, never A's.
        CountDownLatch gateA = new CountDownLatch(1);
        byte[] bytesA = new byte[] { 9, 9, 9 };
        byte[] bytesB = new byte[] { 7, 7, 7 };
        plugin.setArtworkFetcher(src -> {
            if (src.contains("A")) {
                try {
                    gateA.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return bytesA;
            }
            return bytesB;
        });

        PluginCall callA = mockMetadataCallWithArtwork("http://example.com/A.png");
        plugin.setMetadata(callA);
        idleMainLooper(); // run selection for A -> submits the (blocked) fetch, bumps generation to 1

        PluginCall callB = mockMetadataCallWithArtwork("http://example.com/B.png");
        plugin.setMetadata(callB);
        idleMainLooper(); // run selection for B -> bumps generation to 2, submits B's fetch (queued)

        // Let A finish first (it was submitted first on the single thread), then B.
        gateA.countDown();
        assertTrue(plugin.awaitArtworkIdle(5000));
        idleMainLooper(); // run both result-delivery posts; A's is discarded (stale generation)

        assertArrayEquals(bytesB, player.getMediaMetadata().artworkData);
    }

    @Test
    public void selectorIntegrationDecodesSizeSelectedEntry() throws Exception {
        // Three real base64 PNGs of different sizes; the 512-target selector must pick the 512 one
        // (smallest >= target), and the decoded bytes must match that size.
        PluginCall call = mockMetadataCallWithArtworkEntries(new String[][] {
                { createPngDataUrl(128, 128), "128x128" },
                { createPngDataUrl(512, 512), "512x512" },
                { createPngDataUrl(1024, 1024), "1024x1024" }
        });

        plugin.setMetadata(call);
        drainArtwork();

        byte[] artworkData = player.getMediaMetadata().artworkData;
        assertNotNull(artworkData);
        Bitmap decoded = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.length);
        assertEquals(512, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
    }

    @Test
    public void setPositionStatePreservesOmittedFields() {
        // First set full position state.
        PluginCall full = mock(PluginCall.class);
        when(full.getDouble(eq("duration"), anyDouble())).thenReturn(120.0);
        when(full.getDouble(eq("position"), anyDouble())).thenReturn(10.0);
        when(full.getDouble(eq("playbackRate"), anyDouble())).thenReturn(2.0);
        plugin.setPositionState(full);
        idleMainLooper();

        // Second call omits duration/playbackRate: getDouble returns the default arg, which is now the
        // previously stored value. Only position is updated.
        PluginCall partial = mock(PluginCall.class);
        when(partial.getDouble(eq("duration"), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        when(partial.getDouble(eq("position"), anyDouble())).thenReturn(55.0);
        when(partial.getDouble(eq("playbackRate"), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        plugin.setPositionState(partial);
        idleMainLooper();

        assertEquals(120_000L, player.getDuration());
        assertEquals(55_000L, player.getCurrentPosition());
        assertEquals(2.0f, player.getPlaybackParameters().speed, 0.0001f);
    }

    // --- Custom actions -----------------------------------------------------------------------

    @Test
    public void registeringCustomActionPublishesSingleCommandButton() {
        plugin.setActionHandler(mockCustomActionHandlerCall("like", "Like", "heart"));
        idleMainLooper();

        ImmutableList<CommandButton> layout = customLayout();
        assertEquals(1, layout.size());
        CommandButton button = layout.get(0);
        assertNotNull(button.sessionCommand);
        assertEquals("like", button.sessionCommand.customAction);
        assertEquals("Like", String.valueOf(button.displayName));
        assertEquals(CommandButton.ICON_HEART_UNFILLED, button.icon);
    }

    @Test
    public void customActionDoesNotEnableAnyPlayerCommand() {
        plugin.setActionHandler(mockCustomActionHandlerCall("like", "Like", "heart"));
        idleMainLooper();

        // A custom action only adds a session command/button; it must not flip any Player.Command.
        assertFalse(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE));
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT));
        assertFalse(player.isCommandAvailable(Player.COMMAND_STOP));
    }

    @Test
    public void customCommandTapResolvesKeptAliveHandler() throws Exception {
        PluginCall likeCall = mockCustomActionHandlerCall("like", "Like", "heart");
        plugin.setActionHandler(likeCall);
        idleMainLooper();

        MediaSession session = service.getMediaSession();
        ListenableFuture<SessionResult> future = service.getSessionCallback().onCustomCommand(
                session,
                mock(MediaSession.ControllerInfo.class),
                new SessionCommand("like", Bundle.EMPTY),
                Bundle.EMPTY);

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(likeCall).resolve(captor.capture());
        assertEquals("like", captor.getValue().getString("action"));
        assertEquals(SessionResult.RESULT_SUCCESS, future.get().resultCode);
    }

    @Test
    public void customCommandForUnregisteredActionResolvesNoHandlerAndDoesNotThrow() {
        PluginCall likeCall = mockCustomActionHandlerCall("like", "Like", "heart");
        plugin.setActionHandler(likeCall);
        idleMainLooper();

        MediaSession session = service.getMediaSession();
        ListenableFuture<SessionResult> future = service.getSessionCallback().onCustomCommand(
                session,
                mock(MediaSession.ControllerInfo.class),
                new SessionCommand("ghost", Bundle.EMPTY),
                Bundle.EMPTY);

        assertNotNull(future);
        verify(likeCall, never()).resolve(any(JSObject.class));
    }

    @Test
    public void reRegisteringCustomActionReplacesButtonAndReleasesPreviousCall() {
        PluginCall first = mockCustomActionHandlerCall("like", "Like", "heart");
        plugin.setActionHandler(first);
        idleMainLooper();

        PluginCall second = mockCustomActionHandlerCall("like", "Unlike", "heart-filled");
        plugin.setActionHandler(second);
        idleMainLooper();

        // Re-registration releases the previously kept-alive call and keeps the new one live.
        verify(first).release(any());
        verify(second).setKeepAlive(true);

        // The single "like" button now shows the filled icon and the new label (toggle).
        ImmutableList<CommandButton> layout = customLayout();
        assertEquals(1, layout.size());
        CommandButton button = layout.get(0);
        assertEquals("like", button.sessionCommand.customAction);
        assertEquals("Unlike", String.valueOf(button.displayName));
        assertEquals(CommandButton.ICON_HEART_FILLED, button.icon);
    }

    @Test
    public void removeHandlerForCustomActionRemovesButtonAndReleasesCall() {
        PluginCall likeCall = mockCustomActionHandlerCall("like", "Like", "heart");
        plugin.setActionHandler(likeCall);
        idleMainLooper();
        assertEquals(1, customLayout().size());

        PluginCall removeCall = mockRemoveHandlerCall("like");
        plugin.setActionHandler(removeCall);
        idleMainLooper();

        verify(likeCall).release(any());
        assertFalse(plugin.hasActionHandler("like"));
        assertTrue(customLayout().isEmpty());
        verify(removeCall).resolve();
    }

    @Test
    public void onConnectGrantsCustomCommandAndPreservesDefaults() {
        plugin.setActionHandler(mockCustomActionHandlerCall("like", "Like", "heart"));
        idleMainLooper();

        MediaSession session = service.getMediaSession();
        MediaSession.ConnectionResult result = service.getSessionCallback().onConnect(
                session, mock(MediaSession.ControllerInfo.class));

        assertTrue(result.isAccepted);
        // The custom session command is granted...
        assertTrue(result.availableSessionCommands.contains(new SessionCommand("like", Bundle.EMPTY)));
        // ...without dropping the default session commands.
        for (SessionCommand command : MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.commands) {
            assertTrue("default session command should be preserved",
                    result.availableSessionCommands.contains(command));
        }
        // The button is in the custom layout handed to the controller.
        assertEquals(1, result.customLayout.size());
        assertEquals("like", result.customLayout.get(0).sessionCommand.customAction);
    }

    // --- Custom action iconUri + enabled (F-1 / F-2) ------------------------------------------

    @Test
    public void customActionIconUriIsPublishedOnButton() {
        plugin.setActionHandler(
                mockCustomActionHandlerCall("like", "Like", null, "content://app/heart.png", true));
        idleMainLooper();

        CommandButton button = customLayout().get(0);
        assertNotNull("iconUri should be set on the button", button.iconUri);
        assertEquals("content://app/heart.png", String.valueOf(button.iconUri));
    }

    @Test
    public void customActionIconConstantIsFallbackWhenNoIconUri() {
        plugin.setActionHandler(mockCustomActionHandlerCall("like", "Like", "heart"));
        idleMainLooper();

        CommandButton button = customLayout().get(0);
        // The icon constant stays as a fallback (button.icon reflects it) and no iconUri is layered.
        assertEquals(CommandButton.ICON_HEART_UNFILLED, button.icon);
        assertNull("no iconUri when none supplied", button.iconUri);
    }

    @Test
    public void customActionEnabledDefaultsTrue() {
        plugin.setActionHandler(mockCustomActionHandlerCall("like", "Like", "heart"));
        idleMainLooper();

        CommandButton button = customLayout().get(0);
        assertTrue("enabled defaults to true", button.isEnabled);
    }

    @Test
    public void customActionDisabledButtonReflectsFlag() {
        plugin.setActionHandler(
                mockCustomActionHandlerCall("like", "Like", "heart", null, false));
        idleMainLooper();

        ImmutableList<CommandButton> layout = customLayout();
        assertEquals("disabled button still present", 1, layout.size());
        assertFalse("disabled flag reflected on button", layout.get(0).isEnabled);
    }

    @Test
    public void reRegisterToggleUpdatesIconUriAndEnabled() {
        PluginCall first = mockCustomActionHandlerCall("like", "Like", null, "content://a", true);
        plugin.setActionHandler(first);
        idleMainLooper();

        PluginCall second = mockCustomActionHandlerCall("like", "Unlike", null, "content://b", false);
        plugin.setActionHandler(second);
        idleMainLooper();

        // The previously kept-alive call is released on re-registration.
        verify(first).release(any());

        ImmutableList<CommandButton> layout = customLayout();
        assertEquals("re-register replaces (single button)", 1, layout.size());
        CommandButton button = layout.get(0);
        assertEquals("Unlike", String.valueOf(button.displayName));
        assertEquals("content://b", String.valueOf(button.iconUri));
        assertFalse(button.isEnabled);
    }

    // --- Handler-map thread confinement (R4/R5/R6) ---------------------------------------------

    @Test
    public void removedHandlerThenTapDoesNotResolveReleasedCall() {
        // Register play, then remove it (which releases the stored kept-alive call). A later tap must
        // not resolve the released call.
        PluginCall playCall = mockActionHandlerCall("play");
        plugin.setActionHandler(playCall);
        idleMainLooper();
        assertTrue(plugin.hasActionHandler("play"));

        PluginCall removeCall = mockRemoveHandlerCall("play");
        plugin.setActionHandler(removeCall);
        idleMainLooper();
        // The stored call was released by removal; reflect that for the liveness guard.
        when(playCall.isReleased()).thenReturn(true);

        plugin.actionCallback("play");

        verify(playCall, never()).resolve(any(JSObject.class));
        assertFalse(plugin.hasActionHandler("play"));
    }

    @Test
    public void actionCallbackDropsReleasedCallStillInMap() {
        // Exercise the isReleased() guard branch directly: the call is still present in the map but
        // reports released, so actionCallback must NOT resolve it.
        PluginCall playCall = mockActionHandlerCall("play");
        plugin.setActionHandler(playCall);
        idleMainLooper();
        assertTrue(plugin.hasActionHandler("play"));

        when(playCall.isReleased()).thenReturn(true);

        plugin.actionCallback("play");

        verify(playCall, never()).resolve(any(JSObject.class));
    }

    @Test
    public void registerThenImmediateTapResolvesExactlyOnce() {
        PluginCall playCall = mockActionHandlerCall("play");
        plugin.setActionHandler(playCall);
        idleMainLooper();

        plugin.actionCallback("play");

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(playCall, times(1)).resolve(captor.capture());
        assertEquals("play", captor.getValue().getString("action"));
    }

    @Test
    public void registrationAndLayoutPublishSettleConsistently() {
        // After a SINGLE main-looper idle, both the handler map and the published custom layout must
        // reflect the new custom action — registration + publish settle in one turn.
        PluginCall likeCall = mockCustomActionHandlerCall("like", "Like", "heart");
        plugin.setActionHandler(likeCall);
        idleMainLooper();

        assertTrue(plugin.hasActionHandler("like"));
        ImmutableList<CommandButton> layout = customLayout();
        assertEquals(1, layout.size());
        assertEquals("like", layout.get(0).sessionCommand.customAction);
    }

    @Test
    public void reRegisterStillReleasesPreviousCallOnMain() {
        // Register, tap (resolves the first call), then re-register (releases the first, keeps the
        // second). A post-swap tap must resolve only the second call, never the released first one.
        PluginCall first = mockActionHandlerCall("play");
        plugin.setActionHandler(first);
        idleMainLooper();

        plugin.actionCallback("play");
        verify(first, times(1)).resolve(any(JSObject.class));

        PluginCall second = mockActionHandlerCall("play");
        plugin.setActionHandler(second);
        idleMainLooper();

        // The first call was released on the main looper during the swap.
        verify(first).release(any());
        when(first.isReleased()).thenReturn(true);
        verify(second).setKeepAlive(true);

        plugin.actionCallback("play");

        // The released first call is never resolved by the post-swap tap; the second one is.
        verify(first, times(1)).resolve(any(JSObject.class));
        verify(second, times(1)).resolve(any(JSObject.class));
    }

    // --- Read-back getters (U-1) --------------------------------------------------------------

    @Test
    public void getPlaybackStateReturnsCachedState() {
        setPlaybackState("playing");

        PluginCall call = mock(PluginCall.class);
        plugin.getPlaybackState(call);

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(call).resolve(captor.capture());
        assertEquals("playing", captor.getValue().getString("playbackState"));
    }

    @Test
    public void getMetadataReturnsCachedTextFields() throws JSONException {
        PluginCall setCall = mock(PluginCall.class);
        when(setCall.getString(eq("title"), anyString())).thenReturn("Song Title");
        when(setCall.getString(eq("artist"), anyString())).thenReturn("Song Artist");
        when(setCall.getString(eq("album"), anyString())).thenReturn("Song Album");
        when(setCall.getArray("artwork")).thenReturn(null);
        plugin.setMetadata(setCall);
        idleMainLooper();

        PluginCall call = mock(PluginCall.class);
        plugin.getMetadata(call);

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(call).resolve(captor.capture());
        JSObject result = captor.getValue();
        assertEquals("Song Title", result.getString("title"));
        assertEquals("Song Artist", result.getString("artist"));
        assertEquals("Song Album", result.getString("album"));
        // No artwork has ever been set (getArray("artwork") -> null), so the key is omitted.
        assertFalse(result.has("artwork"));
    }

    @Test
    public void getMetadataReturnsArtworkArray() throws JSONException {
        PluginCall setCall = mockMetadataTextCall();
        JSArray artworkArray = new JSArray();
        artworkArray.put(new JSObject().put("src", "http://a/1.png").put("sizes", "96x96"));
        artworkArray.put(new JSObject().put("src", "http://a/2.png").put("sizes", "512x512"));
        when(setCall.getArray("artwork")).thenReturn(artworkArray);
        plugin.setMetadata(setCall);
        idleMainLooper();

        PluginCall call = mock(PluginCall.class);
        plugin.getMetadata(call);

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(call).resolve(captor.capture());
        JSObject result = captor.getValue();
        assertTrue("artwork array should be returned", result.has("artwork"));
        JSONArray artwork = result.getJSONArray("artwork");
        assertEquals(2, artwork.length());
        assertEquals("http://a/1.png", artwork.getJSONObject(0).getString("src"));
    }

    @Test
    public void getMetadataPreservesArtworkWhenKeyAbsent() throws JSONException {
        // First set an artwork array.
        PluginCall setCall = mockMetadataTextCall();
        JSArray artworkArray = new JSArray();
        artworkArray.put(new JSObject().put("src", "http://a/1.png").put("sizes", "96x96"));
        artworkArray.put(new JSObject().put("src", "http://a/2.png").put("sizes", "512x512"));
        when(setCall.getArray("artwork")).thenReturn(artworkArray);
        plugin.setMetadata(setCall);
        idleMainLooper();

        // Then a text-only update with NO artwork key (getArray -> null) must preserve the array.
        PluginCall textOnly = mockMetadataTextCall();
        when(textOnly.getArray("artwork")).thenReturn(null);
        plugin.setMetadata(textOnly);
        idleMainLooper();

        PluginCall call = mock(PluginCall.class);
        plugin.getMetadata(call);

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(call).resolve(captor.capture());
        JSObject result = captor.getValue();
        assertTrue("artwork preserved when key absent", result.has("artwork"));
        JSONArray artwork = result.getJSONArray("artwork");
        assertEquals(2, artwork.length());
        assertEquals("http://a/1.png", artwork.getJSONObject(0).getString("src"));
    }

    @Test
    public void getPositionStateReturnsCachedValues() throws JSONException {
        PluginCall setCall = mock(PluginCall.class);
        when(setCall.getDouble(eq("duration"), anyDouble())).thenReturn(180.0);
        when(setCall.getDouble(eq("position"), anyDouble())).thenReturn(42.0);
        // 1.05 is not exactly representable as a float, so a float round-trip would lose precision;
        // the getDouble path must preserve it exactly (U-1 precision regression guard).
        when(setCall.getDouble(eq("playbackRate"), anyDouble())).thenReturn(1.05);
        plugin.setPositionState(setCall);
        idleMainLooper();

        PluginCall call = mock(PluginCall.class);
        plugin.getPositionState(call);

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(call).resolve(captor.capture());
        JSObject result = captor.getValue();
        assertEquals(180.0, result.getDouble("duration"), 0.0001);
        assertEquals(42.0, result.getDouble("position"), 0.0001);
        assertEquals(1.05, result.getDouble("playbackRate"), 0.0);
    }

    @Test
    public void getPositionStateReturnsDefaultsBeforeAnySet() throws JSONException {
        // No setPositionState yet: the cached defaults (0/0/1) are returned.
        PluginCall call = mock(PluginCall.class);
        plugin.getPositionState(call);

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(call).resolve(captor.capture());
        JSObject result = captor.getValue();
        assertEquals(0.0, result.getDouble("duration"), 0.0001);
        assertEquals(0.0, result.getDouble("position"), 0.0001);
        assertEquals(1.0, result.getDouble("playbackRate"), 0.0001);
    }

    // --- addListener('action') event channel (U-1) --------------------------------------------

    @Test
    public void addListenerReceivesActionEvent() throws JSONException {
        // Register an 'action' listener AND a separate setActionHandler handler: both fire.
        PluginCall listenerCall = mockListenerCall("action");
        plugin.addListener(listenerCall);

        PluginCall playCall = mockActionHandlerCall("play");
        plugin.setActionHandler(playCall);
        idleMainLooper();

        plugin.actionCallback("play", new JSObject());

        // The kept-alive handler still resolves exactly once (per-tap delivery unchanged)...
        verify(playCall, times(1)).resolve(any(JSObject.class));
        // ...and the listener ALSO receives the action event.
        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(listenerCall, atLeastOnce()).resolve(captor.capture());
        assertEquals("play", captor.getValue().getString("action"));
    }

    @Test
    public void addListenerReceivesActionEventWithNoHandlerRegistered() throws JSONException {
        // No setActionHandler handler at all: the event channel must STILL fire (unconditional emit).
        PluginCall listenerCall = mockListenerCall("action");
        plugin.addListener(listenerCall);

        plugin.actionCallback("pause", new JSObject());

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(listenerCall, atLeastOnce()).resolve(captor.capture());
        assertEquals("pause", captor.getValue().getString("action"));
    }

    @Test
    public void actionEventCarriesCustomArgs() throws JSONException {
        PluginCall listenerCall = mockListenerCall("action");
        plugin.addListener(listenerCall);

        JSObject data = new JSObject();
        data.put("count", 3);
        data.put("flag", true);
        data.put("name", "value");
        plugin.actionCallback("like", data);

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(listenerCall, atLeastOnce()).resolve(captor.capture());
        JSObject event = captor.getValue();
        assertEquals("like", event.getString("action"));
        assertEquals(3, event.getInt("count"));
        assertTrue(event.getBoolean("flag"));
        assertEquals("value", event.getString("name"));
    }

    // --- R-3 destroyed guard ------------------------------------------------------------------

    @Test
    public void destroyedGuardDropsLateArtwork() throws Exception {
        // A latch-blocked fetcher keeps the artwork result in flight while the plugin is destroyed.
        CountDownLatch fetchGate = new CountDownLatch(1);
        plugin.setArtworkFetcher(src -> {
            try {
                fetchGate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FIXED_ARTWORK_BYTES;
        });

        PluginCall call = mockMetadataCallWithArtwork("http://example.com/cover.png");
        plugin.setMetadata(call);
        idleMainLooper(); // run selection -> submits the (blocked) fetch

        // Tear the plugin down while the fetch is still gated: must not throw. handleOnDestroy sets the
        // destroyed flag, removes pending main-looper callbacks, then shuts the executor down (which
        // interrupts the blocked fetch).
        plugin.handleOnDestroy();

        // Release the gate (the fetch was interrupted by shutdownNow, but unblock it defensively) and
        // idle: any late result-delivery runnable that still slipped onto the looper must be dropped by
        // the destroyed guard without an NPE touching the released player/state.
        fetchGate.countDown();
        idleMainLooper();
        // Reaching here without an exception is the assertion; the service was torn down too.
        assertNull(getPluginField("service"));
    }

    @Test
    public void existingHandlerStillResolvesExactlyOnceWithListenerRegistered() throws JSONException {
        // Guards the additive emit: with BOTH a listener and a handler, the handler-resolve count is
        // still exactly one (separate mocks — the times(1) handler assertions elsewhere still hold).
        PluginCall listenerCall = mockListenerCall("action");
        plugin.addListener(listenerCall);
        PluginCall playCall = mockActionHandlerCall("play");
        plugin.setActionHandler(playCall);
        idleMainLooper();

        plugin.actionCallback("play");

        verify(playCall, times(1)).resolve(any(JSObject.class));
        verify(listenerCall, atLeastOnce()).resolve(any(JSObject.class));
    }

    // --- artworkload event (R1) ---------------------------------------------------------------

    @Test
    public void artworkLoadEventFiresLoadedTrueOnSuccess() throws JSONException {
        PluginCall listenerCall = mockListenerCall("artworkload");
        plugin.addListener(listenerCall);

        plugin.setArtworkFetcher(src -> FIXED_ARTWORK_BYTES);
        PluginCall call = mockMetadataCallWithArtwork("http://example.com/cover.png");
        plugin.setMetadata(call);
        drainArtwork();

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(listenerCall, atLeastOnce()).resolve(captor.capture());
        JSObject event = captor.getValue();
        assertTrue("loaded should be true on a successful fetch", event.getBoolean("loaded"));
        assertEquals("http://example.com/cover.png", event.getString("src"));
    }

    @Test
    public void artworkLoadEventFiresLoadedFalseOnFetchFailure() throws JSONException {
        PluginCall listenerCall = mockListenerCall("artworkload");
        plugin.addListener(listenerCall);

        plugin.setArtworkFetcher(src -> {
            throw new IOException("boom");
        });
        PluginCall call = mockMetadataCallWithArtwork("http://example.com/missing.png");
        plugin.setMetadata(call);
        drainArtwork();

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(listenerCall, atLeastOnce()).resolve(captor.capture());
        JSObject event = captor.getValue();
        assertFalse("loaded should be false when the fetch fails", event.getBoolean("loaded"));
        assertEquals("http://example.com/missing.png", event.getString("src"));
    }

    @Test
    public void artworkLoadEventFiresLoadedFalseWhenNoUsableSrc() throws JSONException {
        PluginCall listenerCall = mockListenerCall("artworkload");
        plugin.addListener(listenerCall);

        AtomicBoolean fetched = new AtomicBoolean(false);
        plugin.setArtworkFetcher(src -> {
            fetched.set(true);
            return FIXED_ARTWORK_BYTES;
        });
        // Empty artwork array: present key but no usable src -> clear, no fetch, loaded:false with no src.
        PluginCall empty = mockMetadataTextCall();
        when(empty.getArray("artwork")).thenReturn(new JSArray());
        plugin.setMetadata(empty);
        drainArtwork();

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(listenerCall, atLeastOnce()).resolve(captor.capture());
        JSObject event = captor.getValue();
        assertFalse("loaded should be false when no usable src", event.getBoolean("loaded"));
        assertFalse("src must be omitted when there was nothing to attempt", event.has("src"));
        assertFalse("the fetcher must never run for an empty array", fetched.get());
    }

    @Test
    public void artworkLoadEventNotEmittedWhenArtworkKeyAbsent() throws JSONException {
        PluginCall listenerCall = mockListenerCall("artworkload");
        plugin.addListener(listenerCall);

        // Text-only setMetadata (getArray("artwork") -> null): the artwork key is absent, so the
        // previous cover is preserved and NO artworkload event is emitted.
        PluginCall textOnly = mockMetadataTextCall();
        when(textOnly.getArray("artwork")).thenReturn(null);
        plugin.setMetadata(textOnly);
        drainArtwork();

        verify(listenerCall, never()).resolve(any(JSObject.class));
    }

    @Test
    public void artworkLoadEventNotEmittedForStaleGeneration() throws Exception {
        // Mirror staleArtworkResultIsDiscardedByGeneration: request A is gated, request B is newer.
        // Exactly one artworkload must fire, carrying B's src; none may carry A's.
        PluginCall listenerCall = mockListenerCall("artworkload");
        plugin.addListener(listenerCall);

        CountDownLatch gateA = new CountDownLatch(1);
        byte[] bytesA = new byte[] { 9, 9, 9 };
        byte[] bytesB = new byte[] { 7, 7, 7 };
        plugin.setArtworkFetcher(src -> {
            if (src.contains("A")) {
                try {
                    gateA.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return bytesA;
            }
            return bytesB;
        });

        PluginCall callA = mockMetadataCallWithArtwork("http://example.com/A.png");
        plugin.setMetadata(callA);
        idleMainLooper(); // select A -> submit (blocked) fetch, generation = 1

        PluginCall callB = mockMetadataCallWithArtwork("http://example.com/B.png");
        plugin.setMetadata(callB);
        idleMainLooper(); // select B -> generation = 2, submit B's fetch (queued)

        gateA.countDown();
        assertTrue(plugin.awaitArtworkIdle(5000));
        idleMainLooper(); // run both result-delivery posts; A's is discarded (stale generation)

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(listenerCall, atLeastOnce()).resolve(captor.capture());
        // Exactly one artworkload event fired, and it carries B's src (A's stale result emitted nothing).
        assertEquals("exactly one artworkload event should fire", 1, captor.getAllValues().size());
        JSObject event = captor.getValue();
        assertTrue(event.getBoolean("loaded"));
        assertEquals("http://example.com/B.png", event.getString("src"));
    }

    @Test
    public void artworkLoadEventNotEmittedAfterDestroy() throws Exception {
        // Mirror destroyedGuardDropsLateArtwork: a latch-blocked fetcher keeps the result in flight
        // while the plugin is destroyed; the late result-delivery must emit NO artworkload event.
        PluginCall listenerCall = mockListenerCall("artworkload");
        plugin.addListener(listenerCall);

        CountDownLatch fetchGate = new CountDownLatch(1);
        plugin.setArtworkFetcher(src -> {
            try {
                fetchGate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FIXED_ARTWORK_BYTES;
        });

        PluginCall call = mockMetadataCallWithArtwork("http://example.com/cover.png");
        plugin.setMetadata(call);
        idleMainLooper(); // select -> submit the (blocked) fetch

        plugin.handleOnDestroy();

        fetchGate.countDown();
        idleMainLooper(); // any late result-delivery runnable must be dropped by the destroyed guard

        verify(listenerCall, never()).resolve(any(JSObject.class));
    }
}
