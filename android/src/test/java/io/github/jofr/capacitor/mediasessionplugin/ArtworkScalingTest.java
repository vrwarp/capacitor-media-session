package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertArrayEquals;

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
}
