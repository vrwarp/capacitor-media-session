package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.IBinder;

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
}
