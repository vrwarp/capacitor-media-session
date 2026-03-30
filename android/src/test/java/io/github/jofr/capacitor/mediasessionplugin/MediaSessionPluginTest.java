package io.github.jofr.capacitor.mediasessionplugin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MediaSessionPluginTest {
    private MediaSessionPlugin plugin;

    @Before
    public void setUp() {
        plugin = spy(new MediaSessionPlugin());
    }

    @Test
    public void testActionCallbackInvokesNotifyListeners() {
        // 1. Setup supported action
        String actionName = "play";
        PluginCall call = mock(PluginCall.class);
        com.getcapacitor.JSObject callData = new com.getcapacitor.JSObject();
        callData.put("action", actionName);
        
        // Use the actual setActionHandler to register the action
        JSObject registerOptions = new JSObject();
        registerOptions.put("action", actionName);
        PluginCall registerCall = mock(PluginCall.class);
        org.mockito.Mockito.when(registerCall.getString("action")).thenReturn(actionName);
        
        plugin.setActionHandler(registerCall);

        // 2. Trigger callback
        JSObject callbackData = new JSObject();
        plugin.actionCallback(actionName, callbackData);

        // 3. Verify notifyListeners was called with correct arguments
        verify(plugin).notifyListeners(eq("onMediaAction"), any(JSObject.class));
    }

    @Test
    public void testActionCallbackDoesNotNotifyForUnsupportedAction() {
        // 1. Trigger callback for an action that wasn't registered
        String unsupportedAction = "nexttrack";
        JSObject callbackData = new JSObject();
        plugin.actionCallback(unsupportedAction, callbackData);

        // 2. Verify notifyListeners was NOT called
        verify(plugin, never()).notifyListeners(eq("onMediaAction"), any(JSObject.class));
    }
}
