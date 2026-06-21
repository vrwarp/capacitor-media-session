package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;

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

        // Connect the plugin to the service the same way Android would after bindService():
        // through the local binder handed out for intents without an action.
        IBinder binder = service.onBind(new Intent(context, MediaSessionService.class));
        ServiceConnection connection = getServiceConnection();
        connection.onServiceConnected(new ComponentName(context, MediaSessionService.class), binder);
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

    private static void idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle();
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
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("playbackState"), anyString())).thenReturn(state);
        plugin.setPlaybackState(call);
        idleMainLooper();
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
        idleMainLooper();

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
        idleMainLooper();

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
        idleMainLooper();

        assertNull(player.getMediaMetadata().artworkData);
        assertEquals("Song Title", String.valueOf(player.getMediaMetadata().title));
        verify(call).resolve();
    }

    private PluginCall mockMetadataCallWithArtwork(String src) throws JSONException {
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("title"), anyString())).thenReturn("Song Title");
        when(call.getString(eq("artist"), anyString())).thenReturn("Song Artist");
        when(call.getString(eq("album"), anyString())).thenReturn("Song Album");
        JSArray artworkArray = new JSArray();
        artworkArray.put(new JSObject().put("src", src));
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

        assertNull(getPluginField("service"));
    }

    @Test
    public void setPlaybackStateNoneKeepsServiceInAlwaysMode() throws Exception {
        setPluginField("startServiceOnlyDuringPlayback", false);
        setPlaybackState("playing");

        setPlaybackState("none");

        assertNotNull(getPluginField("service"));
        assertEquals(Player.STATE_IDLE, player.getPlaybackState());
    }

    @Test
    public void setPositionStatePropagatesToPlayer() {
        PluginCall call = mock(PluginCall.class);
        when(call.getDouble(eq("duration"), anyDouble())).thenReturn(100.0);
        when(call.getDouble(eq("position"), anyDouble())).thenReturn(50.5);
        when(call.getFloat(eq("playbackRate"), anyFloat())).thenReturn(1.5f);

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

        plugin.actionCallback("nexttrack");

        verify(playCall, never()).resolve(any(JSObject.class));
    }

    @Test
    public void actionCallbackAddsActionToData() {
        PluginCall playCall = mockActionHandlerCall("play");
        plugin.setActionHandler(playCall);

        plugin.actionCallback("play");

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(playCall).resolve(captor.capture());
        assertEquals("play", captor.getValue().getString("action"));
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
}
