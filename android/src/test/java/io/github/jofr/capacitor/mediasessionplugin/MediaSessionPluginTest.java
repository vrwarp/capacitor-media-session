package io.github.jofr.capacitor.mediasessionplugin;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import com.getcapacitor.Bridge;
import androidx.appcompat.app.AppCompatActivity;

@RunWith(RobolectricTestRunner.class)
public class MediaSessionPluginTest {

    private MediaSessionPlugin plugin;
    private Bridge mockBridge;
    private AppCompatActivity mockActivity;

    @Before
    public void setUp() {
        plugin = new MediaSessionPlugin();
        mockBridge = mock(Bridge.class);
        mockActivity = mock(AppCompatActivity.class);
        when(mockBridge.getActivity()).thenReturn(mockActivity);
        when(mockBridge.getContext()).thenReturn(mockActivity);
        plugin.setBridge(mockBridge);
    }

    @Test
    public void testSetMetadata() throws JSONException, IOException {
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("title"), anyString())).thenReturn("Test Title");
        when(call.getString(eq("artist"), anyString())).thenReturn("Test Artist");
        when(call.getString(eq("album"), anyString())).thenReturn("Test Album");

        JSArray emptyArtworkArray = new JSArray();
        when(call.getArray("artwork")).thenReturn(emptyArtworkArray);

        plugin.setMetadata(call);

        verify(call).resolve();
    }

    @Test
    public void testSetPlaybackStatePlaying() {
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("playbackState"), anyString())).thenReturn("playing");

        plugin.setPlaybackState(call);

        verify(call).resolve();
    }

    @Test
    public void testSetPlaybackStatePaused() {
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("playbackState"), anyString())).thenReturn("paused");

        plugin.setPlaybackState(call);

        verify(call).resolve();
    }

    @Test
    public void testSetPlaybackStateNone() {
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("playbackState"), anyString())).thenReturn("none");

        plugin.setPlaybackState(call);

        verify(call).resolve();
    }

    @Test
    public void testSetPositionState() {
        PluginCall call = mock(PluginCall.class);
        when(call.getDouble(eq("duration"), anyDouble())).thenReturn(100.0);
        when(call.getDouble(eq("position"), anyDouble())).thenReturn(50.0);
        when(call.getFloat(eq("playbackRate"), anyFloat())).thenReturn(1.5f);

        plugin.setPositionState(call);

        verify(call).resolve();
    }

    @Test
    public void testSetActionHandlerAndCallback() {
        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("action"), anyString())).thenReturn("play");
        when(call.getString("action")).thenReturn("play");
        when(call.getCallbackId()).thenReturn("test-callback-id");

        plugin.setActionHandler(call);

        verify(call).setKeepAlive(true);

        assertTrue(plugin.hasActionHandler("play"));

        plugin.actionCallback("play");

        // Ensure resolve is called on the saved handler call with some JSObject data
        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(call).resolve(captor.capture());
        assertEquals("play", captor.getValue().getString("action"));
    }
}
