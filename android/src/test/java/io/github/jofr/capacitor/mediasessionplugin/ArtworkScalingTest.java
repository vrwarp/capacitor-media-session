package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Pure unit tests for {@link MediaSessionPlugin#computeScaledDimensions(int, int, int)}.
 * No Android runtime needed — this pins the artwork downscale math that keeps cover bitmaps
 * under the Binder transaction limit (see {@code bitmapToArtworkData}).
 */
public class ArtworkScalingTest {
    @Test
    public void landscapeIsScaledByLongEdge() {
        assertArrayEquals(new int[] { 512, 384 },
                MediaSessionPlugin.computeScaledDimensions(1024, 768, 512));
    }

    @Test
    public void portraitIsScaledByLongEdge() {
        assertArrayEquals(new int[] { 384, 512 },
                MediaSessionPlugin.computeScaledDimensions(768, 1024, 512));
    }

    @Test
    public void withinBoundsIsUnchanged() {
        assertArrayEquals(new int[] { 300, 300 },
                MediaSessionPlugin.computeScaledDimensions(300, 300, 512));
    }

    @Test
    public void exactlyAtBoundIsUnchanged() {
        assertArrayEquals(new int[] { 512, 256 },
                MediaSessionPlugin.computeScaledDimensions(512, 256, 512));
    }

    @Test
    public void degenerateDimensionsArePassedThrough() {
        assertArrayEquals(new int[] { 0, 0 },
                MediaSessionPlugin.computeScaledDimensions(0, 0, 512));
    }

    // --- computeInSampleSize (half-dimension power-of-two subsampling) -------------------------

    @Test
    public void inSampleSizeHalvesUntilBelowMaxEdge() {
        // 6000 -> 3000 -> 1500 -> 750 (>=512) then 375 (<512): stops at 8.
        assertEquals(8, MediaSessionPlugin.computeInSampleSize(6000, 6000, 512));
    }

    @Test
    public void inSampleSizeJustAboveDouble() {
        // 1024 -> 512 (>=512) then 256 (<512): stops at 2.
        assertEquals(2, MediaSessionPlugin.computeInSampleSize(1024, 1024, 512));
    }

    @Test
    public void inSampleSizeNonSquareUsesBothEdges() {
        // The shorter half-edge (500/2=250 < 512) prevents any subsampling.
        assertEquals(1, MediaSessionPlugin.computeInSampleSize(1000, 500, 512));
    }

    @Test
    public void inSampleSizeExactlyAtMaxEdgeDoesNotSubsample() {
        // 512/2 = 256 < 512 on the first check: stays at 1.
        assertEquals(1, MediaSessionPlugin.computeInSampleSize(512, 512, 512));
    }

    @Test
    public void inSampleSizeDegenerateIsOne() {
        assertEquals(1, MediaSessionPlugin.computeInSampleSize(0, 0, 512));
    }

    @Test
    public void inSampleSizeWideImageBoundedByShortEdge() {
        // 8000x2000: 2000/2=1000 (>=512) then 2000/2/2=500 (<512): stops at 2.
        assertEquals(2, MediaSessionPlugin.computeInSampleSize(8000, 2000, 512));
    }

    // --- resolveRedirect (per-hop redirect decision for httpToArtworkData) ---------------------

    @Test
    public void resolveRedirectSameProtocolAbsolute() {
        assertEquals("http://a.com/y",
                MediaSessionPlugin.resolveRedirect(301, "http://a.com/x", "http://a.com/y"));
    }

    @Test
    public void resolveRedirectCrossProtocolHttpToHttps() {
        assertEquals("https://i.scdn.co/img",
                MediaSessionPlugin.resolveRedirect(301, "http://i.scdn.co/img", "https://i.scdn.co/img"));
    }

    @Test
    public void resolveRedirectCrossProtocolHttpsToHttp() {
        assertEquals("http://i.scdn.co/img",
                MediaSessionPlugin.resolveRedirect(302, "https://i.scdn.co/img", "http://i.scdn.co/img"));
    }

    @Test
    public void resolveRedirectRelativeAbsolutePath() {
        assertEquals("https://cdn.com/c/d.png",
                MediaSessionPlugin.resolveRedirect(302, "https://cdn.com/a/b.png", "/c/d.png"));
    }

    @Test
    public void resolveRedirectRelativeSiblingPath() {
        assertEquals("https://cdn.com/a/e.png",
                MediaSessionPlugin.resolveRedirect(302, "https://cdn.com/a/b.png", "e.png"));
    }

    @Test
    public void resolveRedirect307Absolute() {
        assertEquals("https://a.com/moved",
                MediaSessionPlugin.resolveRedirect(307, "https://a.com/x", "https://a.com/moved"));
    }

    @Test
    public void resolveRedirect308Relative() {
        assertEquals("https://a.com/perm/here",
                MediaSessionPlugin.resolveRedirect(308, "https://a.com/perm/x", "here"));
    }

    @Test
    public void resolveRedirect303() {
        assertEquals("https://a.com/other",
                MediaSessionPlugin.resolveRedirect(303, "https://a.com/x", "https://a.com/other"));
    }

    @Test
    public void resolveRedirectNon3xxReturnsNull() {
        assertNull(MediaSessionPlugin.resolveRedirect(200, "http://a.com/x", "http://a.com/y"));
        assertNull(MediaSessionPlugin.resolveRedirect(404, "http://a.com/x", "http://a.com/y"));
        assertNull(MediaSessionPlugin.resolveRedirect(500, "http://a.com/x", "http://a.com/y"));
    }

    @Test
    public void resolveRedirectNonHttpSchemeReturnsNull() {
        assertNull(MediaSessionPlugin.resolveRedirect(301, "http://a.com/x", "ftp://a.com/y"));
        assertNull(MediaSessionPlugin.resolveRedirect(301, "http://a.com/x", "file:///etc/passwd"));
        assertNull(MediaSessionPlugin.resolveRedirect(301, "http://a.com/x", "data:text/plain,hi"));
    }

    @Test
    public void resolveRedirectNullOrBlankLocationReturnsNull() {
        assertNull(MediaSessionPlugin.resolveRedirect(301, "http://a.com/x", null));
        assertNull(MediaSessionPlugin.resolveRedirect(301, "http://a.com/x", ""));
        assertNull(MediaSessionPlugin.resolveRedirect(301, "http://a.com/x", "   "));
    }

    @Test
    public void resolveRedirectSelfLoopReturnsNull() {
        assertNull(MediaSessionPlugin.resolveRedirect(301, "https://a.com/x", "https://a.com/x"));
        // Relative self form resolving to the same absolute URL.
        assertNull(MediaSessionPlugin.resolveRedirect(301, "https://a.com/x", "x"));
    }

    @Test
    public void resolveRedirectMalformedLocationReturnsNull() {
        // A Location whose authority is malformed makes new URL(...) throw MalformedURLException,
        // which resolveRedirect catches and turns into null (no exception escapes).
        assertNull(MediaSessionPlugin.resolveRedirect(301, "http://a.com/x", "http://[bad"));
    }
}
