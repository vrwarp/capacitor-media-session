package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
}
