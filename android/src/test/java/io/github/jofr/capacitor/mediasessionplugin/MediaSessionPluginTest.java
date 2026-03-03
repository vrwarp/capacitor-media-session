package io.github.jofr.capacitor.mediasessionplugin;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

import android.content.Intent;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;

import com.getcapacitor.JSArray;
import com.getcapacitor.PluginCall;
import com.getcapacitor.Bridge;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE)
public class MediaSessionPluginTest {

    private MediaSessionPlugin plugin;
    private Bridge mockBridge;
    private AppCompatActivity mockActivity;
    private MediaSessionService realService;
    private Player player;

    @Before
    public void setUp() throws Exception {
        plugin = new MediaSessionPlugin();
        mockBridge = mock(Bridge.class);
        mockActivity = mock(AppCompatActivity.class);
        when(mockBridge.getActivity()).thenReturn(mockActivity);
        when(mockBridge.getContext()).thenReturn(mockActivity);
        plugin.setBridge(mockBridge);

        realService = Robolectric.buildService(MediaSessionService.class).create().get();
        Intent intent = new Intent(mockActivity, mockActivity.getClass());
        realService.connectAndInitialize(plugin, intent);

        Field serviceField = MediaSessionPlugin.class.getDeclaredField("service");
        serviceField.setAccessible(true);
        serviceField.set(plugin, realService);

        player = realService.getPlayer();
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

        MediaMetadata metadata = player.getMediaMetadata();
        assertNotNull(metadata);
        assertEquals("Song Title", metadata.title.toString());
        assertEquals("Song Artist", metadata.artist.toString());
        assertEquals("Song Album", metadata.albumTitle.toString());

        verify(call).resolve();
    }

    @Test
    public void testSetPlaybackStatePropagatesToMediaSession() throws Exception {
        PluginCall call = mock(PluginCall.class);

        // Test playing
        when(call.getString(eq("playbackState"), anyString())).thenReturn("playing");
        plugin.setPlaybackState(call);

        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertTrue(player.getPlayWhenReady());

        // Test paused
        when(call.getString(eq("playbackState"), anyString())).thenReturn("paused");
        plugin.setPlaybackState(call);

        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertFalse(player.getPlayWhenReady());

        // Test none
        Field configField = MediaSessionPlugin.class.getDeclaredField("startServiceOnlyDuringPlayback");
        configField.setAccessible(true);
        configField.set(plugin, false);

        when(call.getString(eq("playbackState"), anyString())).thenReturn("none");
        plugin.setPlaybackState(call);

        assertEquals(Player.STATE_IDLE, player.getPlaybackState());

        verify(call, times(3)).resolve();
    }

    @Test
    public void testSetPositionStatePropagatesToMediaSession() {
        PluginCall call = mock(PluginCall.class);
        when(call.getDouble(eq("duration"), anyDouble())).thenReturn(100.0);
        when(call.getDouble(eq("position"), anyDouble())).thenReturn(50.5);
        when(call.getFloat(eq("playbackRate"), anyFloat())).thenReturn(1.5f);

        plugin.setPositionState(call);

        assertEquals(100000L, player.getDuration());
        assertEquals(1.5f, player.getPlaybackParameters().speed, 0.001);

        verify(call).resolve();
    }
}
