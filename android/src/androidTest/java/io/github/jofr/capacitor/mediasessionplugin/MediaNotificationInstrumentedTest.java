package io.github.jofr.capacitor.mediasessionplugin;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.service.notification.StatusBarNotification;

import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ServiceTestRule;
import androidx.test.uiautomator.UiDevice;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * L3 (instrumented — runs on a device/emulator via
 * {@code ./gradlew :jofr-capacitor-media-session:connectedDebugAndroidTest}).
 *
 * This is the only layer that verifies the REAL OS behaviour the headless tests cannot:
 *  (a) a foreground media notification is actually POSTED on play (FLAG_FOREGROUND_SERVICE proves a
 *      real startForeground, not a Robolectric shadow), (b) its metadata renders, (c) it CLEARS on
 *      stop, and that a real androidx.media3.session.MediaController can connect to the published
 *      session (the end-to-end proof of the addSession fix). It binds the service directly and drives
 *      {@link WebViewProxyPlayer#updateSessionState}, which carries supportedActions, so no
 *      JS/WebView layer is needed.
 *
 * NOT covered here (genuinely device-physical): real Bluetooth/AVRCP & Android Auto routing, and the
 * API-31+ background-first-play FGS-start denial.
 */
@RunWith(AndroidJUnit4.class)
public class MediaNotificationInstrumentedTest {

    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private static Set<String> actions(String... a) {
        Set<String> s = new HashSet<>();
        java.util.Collections.addAll(s, a);
        return s;
    }

    private WebViewProxyPlayer bindAndGetPlayer() throws Exception {
        IBinder binder = serviceRule.bindService(new Intent(ctx(), MediaSessionService.class));
        // Same package as the service, so the package-private LocalBinder.getService() is reachable.
        MediaSessionService service = ((MediaSessionService.LocalBinder) binder).getService();
        assertNotNull("service bound", service);
        return service.getPlayer();
    }

    private void onMain(Runnable r) {
        getInstrumentation().runOnMainSync(r);
    }

    /** Poll the app's own posted notifications until {@code predicate} matches or the deadline passes. */
    private StatusBarNotification awaitNotification(java.util.function.Predicate<StatusBarNotification> predicate)
            throws InterruptedException {
        NotificationManager nm = (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                if (predicate.test(sbn)) return sbn;
            }
            Thread.sleep(100L);
        }
        return null;
    }

    private boolean appHasNoActiveNotification() {
        NotificationManager nm = (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        return nm.getActiveNotifications().length == 0;
    }

    @Test
    public void postsForegroundMediaNotificationOnPlay_thenClearsOnStop() throws Exception {
        WebViewProxyPlayer player = bindAndGetPlayer();

        onMain(() -> player.updateSessionState(
                "playing", "Chapter 1", "Lewis Carroll", "Alice", null,
                600.0, 5.0, 1.0,
                actions("play", "pause", "seekto", "nexttrack", "previoustrack", "stop")));

        StatusBarNotification sbn = awaitNotification(n ->
                (n.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);
        assertNotNull("a foreground media notification should be posted while playing", sbn);

        Notification n = sbn.getNotification();
        // metadata rendered into the notification
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        assertTrue("notification carries the chapter title",
                title != null && title.toString().contains("Chapter 1"));
        // transport actions present (play/pause/seek/next/prev)
        assertTrue("notification has transport actions", n.actions != null && n.actions.length > 0);

        // clears when playback stops (no STATE_IDLE flap leaving a stale notification)
        onMain(() -> player.updateSessionState("none", "", "", "", null, 0.0, 0.0, 1.0, actions()));
        long deadline = System.currentTimeMillis() + 5_000L;
        boolean cleared = false;
        while (System.currentTimeMillis() < deadline) {
            if (appHasNoActiveNotification()) { cleared = true; break; }
            Thread.sleep(100L);
        }
        assertTrue("media notification should clear after stop", cleared);
    }

    @Test
    public void mediaControllerConnectsToPublishedSession() throws Exception {
        // End-to-end proof of the addSession() fix: a real Media3 MediaController can connect to the
        // service's published session over Binder. (Refuted headless; this is why it lives at L3.)
        SessionToken token = new SessionToken(ctx(), new ComponentName(ctx(), MediaSessionService.class));
        ListenableFuture<MediaController> future = new MediaController.Builder(ctx(), token).buildAsync();
        MediaController controller = null;
        try {
            controller = future.get(10, TimeUnit.SECONDS);
            assertNotNull("MediaController should connect to the MediaSessionService", controller);
        } catch (TimeoutException e) {
            fail("MediaController did not connect within 10s — session not published (addSession regression?)");
        } finally {
            final MediaController c = controller;
            if (c != null) onMain(c::release);
            else MediaController.releaseFuture(future);
        }
    }

    @Test
    public void notificationShadeOpensViaUiAutomator() throws Exception {
        // Smoke that the rendered system shade is reachable (the surface lock-screen/notification
        // controls live on). Detailed button-tap routing is exercised by the adb device-smoke.
        WebViewProxyPlayer player = bindAndGetPlayer();
        onMain(() -> player.updateSessionState(
                "playing", "Chapter 1", "Lewis Carroll", "Alice", null,
                600.0, 0.0, 1.0, actions("play", "pause", "stop")));
        assertNotNull(awaitNotification(n ->
                (n.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0));

        UiDevice device = UiDevice.getInstance(getInstrumentation());
        device.openNotification();
        device.waitForIdle();
        // Leave assertions on specific shade text out: action labels are locale/OEM-sensitive.
        // Reaching here without throwing exercises the shade path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            device.pressBack();
        }
    }
}
