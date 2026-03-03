package io.github.jofr.capacitor.mediasessionplugin;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

import android.content.Intent;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.getcapacitor.Bridge;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Field;
import android.content.ComponentName;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE)
public class MediaSessionPluginTest {

    private MediaSessionPlugin plugin;
    private Bridge mockBridge;
    private AppCompatActivity mockActivity;
    private MediaSessionService realService;
    private MediaControllerCompat controller;

    @Before
    public void setUp() throws Exception {
        plugin = new MediaSessionPlugin();
        mockBridge = mock(Bridge.class);
        mockActivity = mock(AppCompatActivity.class);
        when(mockBridge.getActivity()).thenReturn(mockActivity);
        when(mockBridge.getContext()).thenReturn(mockActivity);
        plugin.setBridge(mockBridge);

        // Setup real MediaSessionService using Robolectric
        realService = Robolectric.setupService(MediaSessionService.class);
        Intent intent = new Intent(mockActivity, mockActivity.getClass());

        // MediaSessionCompat requires a valid context to resolve a MediaButtonReceiver.
        // Robolectric's mocked Application context can't resolve it directly out of the box
        // if the AndroidManifest isn't parsed in a certain way, so we provide an explicit application context.
        // Actually, MediaSessionCompat needs the manifest to contain a MediaButtonReceiver.
        // Since this is a library, the manifest might not be fully parsed.
        // Let's use Robolectric's shadow capabilities or just avoid connectAndInitialize crashing by catching.
        // Or we can mock the Service intent and try wrapping context.

        // Actually, in Robolectric, a common workaround for MediaSessionCompat is to use:
        // RobolectricTestRunner with @Config(manifest=Config.NONE) or provide a fake manifest.
        // Let's just catch and ignore, but wait, if it crashes we can't test it.
        // Let's spy realService or we just use `Robolectric.buildService()`
        realService = Robolectric.buildService(MediaSessionService.class).create().get();
        try {
            realService.connectAndInitialize(plugin, intent);
        } catch (IllegalArgumentException e) {
            // Robolectric throws MediaButtonReceiver component may not be null.
            // In Android X / Support Library, MediaSessionCompat tries to find MediaButtonReceiver in Manifest
            // If it can't, it throws. Let's explicitly setup the MediaSessionCompat via reflection to bypass this.
            // We can just create a dummy MediaSessionCompat and inject it into realService.

            ComponentName mockComponent = new ComponentName(org.robolectric.RuntimeEnvironment.getApplication(), "MockReceiver");
            MediaSessionCompat dummySession = new MediaSessionCompat(org.robolectric.RuntimeEnvironment.getApplication(), "TestSession", mockComponent, null);
            Field mediaSessionField = MediaSessionService.class.getDeclaredField("mediaSession");
            mediaSessionField.setAccessible(true);
            mediaSessionField.set(realService, dummySession);

            // Re-run the rest of the initialization that would normally happen.
            Field playbackStateBuilderField = MediaSessionService.class.getDeclaredField("playbackStateBuilder");
            playbackStateBuilderField.setAccessible(true);
            playbackStateBuilderField.set(realService, new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0F));
            dummySession.setPlaybackState((PlaybackStateCompat) ((PlaybackStateCompat.Builder)playbackStateBuilderField.get(realService)).build());

            Field mediaMetadataBuilderField = MediaSessionService.class.getDeclaredField("mediaMetadataBuilder");
            mediaMetadataBuilderField.setAccessible(true);
            mediaMetadataBuilderField.set(realService, new MediaMetadataCompat.Builder().putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0));
            dummySession.setMetadata(((MediaMetadataCompat.Builder)mediaMetadataBuilderField.get(realService)).build());

            Field pluginField = MediaSessionService.class.getDeclaredField("plugin");
            pluginField.setAccessible(true);
            pluginField.set(realService, plugin);
        }

        // Inject real service into plugin
        Field serviceField = MediaSessionPlugin.class.getDeclaredField("service");
        serviceField.setAccessible(true);
        serviceField.set(plugin, realService);

        // Access the MediaSessionCompat instance from the service to build a MediaController
        Field mediaSessionField = MediaSessionService.class.getDeclaredField("mediaSession");
        mediaSessionField.setAccessible(true);
        MediaSessionCompat mediaSession = (MediaSessionCompat) mediaSessionField.get(realService);

        controller = new MediaControllerCompat(mockActivity, mediaSession.getSessionToken());
    }

    @Test
    public void testSetMetadataPropagatesToMediaSession() throws JSONException, IOException {
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("title"), anyString())).thenReturn("Song Title");
        when(call.getString(eq("artist"), anyString())).thenReturn("Song Artist");
        when(call.getString(eq("album"), anyString())).thenReturn("Song Album");

        JSArray emptyArtworkArray = new JSArray();
        when(call.getArray("artwork")).thenReturn(emptyArtworkArray);

        plugin.setMetadata(call);

        MediaMetadataCompat metadata = controller.getMetadata();
        assertNotNull(metadata);
        assertEquals("Song Title", metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
        assertEquals("Song Artist", metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST));
        assertEquals("Song Album", metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM));

        verify(call).resolve();
    }

    @Test
    public void testSetPlaybackStatePropagatesToMediaSession() throws Exception {
        PluginCall call = mock(PluginCall.class);

        // Test playing
        when(call.getString(eq("playbackState"), anyString())).thenReturn("playing");
        plugin.setPlaybackState(call);

        PlaybackStateCompat state = controller.getPlaybackState();
        assertNotNull(state);
        assertEquals(PlaybackStateCompat.STATE_PLAYING, state.getState());

        // Test paused
        when(call.getString(eq("playbackState"), anyString())).thenReturn("paused");
        plugin.setPlaybackState(call);

        state = controller.getPlaybackState();
        assertNotNull(state);
        assertEquals(PlaybackStateCompat.STATE_PAUSED, state.getState());

        // Test none
        // Need to bypass auto-stop unbinding logic for unit tests easily by setting config
        Field configField = MediaSessionPlugin.class.getDeclaredField("startServiceOnlyDuringPlayback");
        configField.setAccessible(true);
        configField.set(plugin, false);

        when(call.getString(eq("playbackState"), anyString())).thenReturn("none");
        plugin.setPlaybackState(call);

        state = controller.getPlaybackState();
        assertNotNull(state);
        assertEquals(PlaybackStateCompat.STATE_NONE, state.getState());

        verify(call, times(3)).resolve();
    }

    @Test
    public void testSetPositionStatePropagatesToMediaSession() {
        PluginCall call = mock(PluginCall.class);
        when(call.getDouble(eq("duration"), anyDouble())).thenReturn(100.0);
        when(call.getDouble(eq("position"), anyDouble())).thenReturn(50.5);
        when(call.getFloat(eq("playbackRate"), anyFloat())).thenReturn(1.5f);

        plugin.setPositionState(call);

        MediaMetadataCompat metadata = controller.getMetadata();
        assertNotNull(metadata);
        assertEquals(100000L, metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION));

        PlaybackStateCompat state = controller.getPlaybackState();
        assertNotNull(state);
        assertEquals(50500L, state.getPosition());
        assertEquals(1.5f, state.getPlaybackSpeed(), 0.001);

        verify(call).resolve();
    }
}
