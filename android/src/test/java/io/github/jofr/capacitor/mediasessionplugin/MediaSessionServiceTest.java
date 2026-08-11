package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;

import com.getcapacitor.JSObject;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

/** Tests for the Media3 session service lifecycle and binding behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MediaSessionServiceTest {
    private ServiceController<MediaSessionService> serviceController;
    private MediaSessionService service;

    @Before
    public void setUp() {
        serviceController = Robolectric.buildService(MediaSessionService.class).create();
        service = serviceController.get();
    }

    @After
    public void tearDown() {
        if (serviceController != null) {
            serviceController.destroy();
            serviceController = null;
        }
    }

    @Test
    public void onCreateInitializesPlayerAndSession() {
        assertNotNull(service.getPlayer());
        assertNotNull(service.onGetSession(null));
        assertSame(service.getPlayer(), service.onGetSession(null).getPlayer());
    }

    @Test
    public void freshServiceHasEmptyCustomLayoutAndSessionCallback() {
        // No custom actions registered yet: the session's custom layout starts empty and a session
        // callback is attached so custom-command taps can be routed back to the plugin.
        assertNotNull(service.getMediaSession());
        assertTrue(service.getMediaSession().getCustomLayout().isEmpty());
        assertNotNull(service.getSessionCallback());
    }

    @Test
    public void localBindReturnsLocalBinder() {
        Intent intent = new Intent(service, MediaSessionService.class);

        IBinder binder = service.onBind(intent);

        assertTrue(binder instanceof MediaSessionService.LocalBinder);
        assertSame(service, ((MediaSessionService.LocalBinder) binder).getService());
    }

    @Test
    public void media3BindIsDelegatedToMedia3() {
        Intent intent = new Intent(androidx.media3.session.MediaSessionService.SERVICE_INTERFACE);
        intent.setClass(service, MediaSessionService.class);

        IBinder binder = service.onBind(intent);

        assertNotNull(binder);
        assertTrue(!(binder instanceof MediaSessionService.LocalBinder));
    }

    @Test
    public void destroyReleasesSessionAndPlayer() {
        assertNotNull(service.getPlayer());

        serviceController.destroy();
        serviceController = null;

        assertNull(service.getPlayer());
    }

    @Test
    public void onStartCommandReturnsNotSticky() {
        // The proxy mirrors WebView audio and has no native resume path, so the OS must not
        // resurrect the service as a sessionless zombie.
        int result = service.onStartCommand(new Intent(service, MediaSessionService.class), 0, 1);

        assertEquals(Service.START_NOT_STICKY, result);
    }

    @Test
    public void onTaskRemovedStopsServiceWhenNotPlaying() {
        // Default proxy state is idle (playbackState "none"), so a swiped-away task must stop the
        // service rather than leave a dead notification behind.
        service.onTaskRemoved(new Intent(service, MediaSessionService.class));

        assertTrue(shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void onTaskRemovedKeepsServiceWhilePlaying() {
        service.getPlayer().updateSessionState(
                "playing", "Title", "Artist", "Album", null, 100.0, 0.0, 1.0,
                java.util.Set.of("play", "pause"));
        shadowOf(Looper.getMainLooper()).idle();

        service.onTaskRemoved(new Intent(service, MediaSessionService.class));

        assertFalse(shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void twoServiceInstancesDoNotCollideOnSessionId() {
        // The unique-session-id guard (AtomicInteger + MediaSession.Builder.setId) lets more than
        // one MediaSession live in the same process without colliding on the default empty id
        // ("Session ID must be unique"). Building two more services alongside the @Before one and
        // reaching the assertions without an IllegalStateException IS the assertion; dropping the
        // setId guard would make the second create() throw.
        ServiceController<MediaSessionService> a = Robolectric.buildService(MediaSessionService.class).create();
        ServiceController<MediaSessionService> b = Robolectric.buildService(MediaSessionService.class).create();
        try {
            assertNotNull(a.get().getPlayer());
            assertNotNull(b.get().getPlayer());
        } finally {
            a.destroy();
            b.destroy();
        }
    }

    // --- Custom-action args marshalling (F-1) --------------------------------------------------

    @Test
    public void onCustomCommandMarshalsArgsToPlugin() throws Exception {
        MediaSessionPlugin mockPlugin = mock(MediaSessionPlugin.class);
        service.setPlugin(mockPlugin);

        Bundle args = new Bundle();
        args.putString("name", "value");
        args.putBoolean("flag", true);
        args.putInt("count", 7);

        ListenableFuture<SessionResult> future = service.getSessionCallback().onCustomCommand(
                service.getMediaSession(),
                mock(MediaSession.ControllerInfo.class),
                new SessionCommand("like", Bundle.EMPTY),
                args);

        ArgumentCaptor<JSObject> captor = ArgumentCaptor.forClass(JSObject.class);
        verify(mockPlugin).actionCallback(eq("like"), captor.capture());
        JSObject data = captor.getValue();
        assertEquals("value", data.getString("name"));
        assertTrue(data.getBoolean("flag"));
        assertEquals(7, data.getInt("count"));

        assertEquals(SessionResult.RESULT_SUCCESS, future.get().resultCode);
    }

    @Test
    public void bundleToJSObjectCoversCommonTypes() throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString("s", "str");
        bundle.putBoolean("b", false);
        bundle.putInt("i", 1);
        bundle.putLong("l", 2L);
        bundle.putDouble("d", 3.5);
        bundle.putFloat("f", 4.5f);
        bundle.putString("nullable", null); // null values are skipped

        JSObject obj = MediaSessionService.bundleToJSObject(bundle);

        assertEquals("str", obj.getString("s"));
        assertFalse(obj.getBoolean("b"));
        assertEquals(1, obj.getInt("i"));
        assertEquals(2L, obj.getLong("l"));
        assertEquals(3.5, obj.getDouble("d"), 0.0001);
        assertEquals(4.5, obj.getDouble("f"), 0.0001);
        assertFalse(obj.has("nullable"));
    }

    @Test
    public void bundleToJSObjectHandlesNullBundle() {
        JSObject obj = MediaSessionService.bundleToJSObject(null);
        assertNotNull(obj);
        assertEquals(0, obj.length());
    }

    /**
     * Fails the first {@code failures} session-creation attempts, the way a
     * "Session ID must be unique" collision against a not-yet-released prior session does.
     */
    static class FlakySessionService extends MediaSessionService {
        static int failures = 0;
        int attempts = 0;
        final List<String> attemptedIds = new ArrayList<>();

        @Override
        MediaSession createSession(String sessionId) {
            attempts++;
            attemptedIds.add(sessionId);
            if (attempts <= failures) {
                throw new IllegalStateException("Session ID must be unique. ID=" + sessionId);
            }
            return super.createSession(sessionId);
        }
    }

    private ServiceController<FlakySessionService> flakyController;

    private FlakySessionService startFlakyService(int failures) {
        FlakySessionService.failures = failures;
        flakyController = Robolectric.buildService(FlakySessionService.class).create();
        return flakyController.get();
    }

    @After
    public void tearDownFlaky() {
        FlakySessionService.failures = 0;
        if (flakyController != null) {
            flakyController.destroy();
            flakyController = null;
        }
    }

    @Test
    public void retriesSessionCreationOnceWithAFreshId() {
        FlakySessionService service = startFlakyService(1);

        assertEquals("should have retried exactly once", 2, service.attempts);
        assertNotEquals("the retry must not reuse the colliding id",
                service.attemptedIds.get(0), service.attemptedIds.get(1));
        assertNotNull("the retry should have produced a session", service.getMediaSession());
        assertNull("a recovered session is not a degraded start", service.getSessionFailureReason());
    }

    @Test
    public void degradesInsteadOfThrowingWhenSessionCreationKeepsFailing() {
        // The whole point: this runs inside Service.onCreate, where throwing kills the app process
        // on launch. Starting without a session must be survivable.
        FlakySessionService service = startFlakyService(Integer.MAX_VALUE);

        assertEquals("should have tried exactly twice", 2, service.attempts);
        assertNull("no session could be built", service.getMediaSession());
        assertNull(service.onGetSession(null));
        assertNotNull("the degradation must be reportable to JS", service.getSessionFailureReason());
        assertTrue(service.getSessionFailureReason().contains("IllegalStateException"));
        assertNotNull("the player is still created so playback state has somewhere to go",
                service.getPlayer());
    }

    @Test
    public void degradedServiceStillBindsAndTakesCustomActionsWithoutCrashing() {
        FlakySessionService service = startFlakyService(Integer.MAX_VALUE);

        assertNotNull("the local binder must still work so the plugin can connect",
                service.onBind(new Intent()));
        // Dropped with a warning rather than NPEing on the absent session.
        service.updateCustomActions(new ArrayList<>());
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void degradedServiceReleasesItsPlayerOnDestroy() {
        FlakySessionService service = startFlakyService(Integer.MAX_VALUE);
        assertNotNull(service.getPlayer());

        flakyController.destroy();
        flakyController = null;

        assertNull("the unowned player must not outlive the service", service.getPlayer());
    }
}
