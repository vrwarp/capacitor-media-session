package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
}
