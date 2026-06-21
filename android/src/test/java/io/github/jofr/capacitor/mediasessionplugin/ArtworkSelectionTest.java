package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for the artwork-selection helpers {@link MediaSessionPlugin#parseMaxEdge(String)} and
 * {@link MediaSessionPlugin#selectArtworkSrc(java.util.List, int)}. These pin the single-image
 * selection that keeps {@code setMetadata} to one fetch per metadata update.
 *
 * <p>Runs under Robolectric (not a bare JUnit test like {@link ArtworkScalingTest}) because
 * {@code selectArtworkSrc} operates on real {@link JSONObject}s and the Android unit-test classpath
 * otherwise stubs {@code org.json} to throw "not mocked". The logic under test is still pure.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ArtworkSelectionTest {
    private static final int TARGET = 512;

    private static JSONObject entry(String src, String sizes) {
        try {
            JSONObject o = new JSONObject();
            if (src != null) {
                o.put("src", src);
            }
            if (sizes != null) {
                o.put("sizes", sizes);
            }
            return o;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void parseMaxEdgePicksLargestAcrossTokens() {
        assertEquals(128, MediaSessionPlugin.parseMaxEdge("96x96 128x128"));
    }

    @Test
    public void parseMaxEdgeSingleToken() {
        assertEquals(512, MediaSessionPlugin.parseMaxEdge("512x512"));
    }

    @Test
    public void parseMaxEdgeNonSquareUsesLongEdge() {
        assertEquals(1024, MediaSessionPlugin.parseMaxEdge("1024x768"));
    }

    @Test
    public void parseMaxEdgeIsCaseInsensitive() {
        assertEquals(128, MediaSessionPlugin.parseMaxEdge("128X128"));
    }

    @Test
    public void parseMaxEdgeEmptyOrNullOrGarbageIsZero() {
        assertEquals(0, MediaSessionPlugin.parseMaxEdge(""));
        assertEquals(0, MediaSessionPlugin.parseMaxEdge(null));
        assertEquals(0, MediaSessionPlugin.parseMaxEdge("garbage"));
        assertEquals(0, MediaSessionPlugin.parseMaxEdge("128"));
        assertEquals(0, MediaSessionPlugin.parseMaxEdge("x"));
    }

    @Test
    public void parseMaxEdgeAnyIsSentinel() {
        assertEquals(MediaSessionPlugin.ANY_EDGE, MediaSessionPlugin.parseMaxEdge("any"));
        assertEquals(MediaSessionPlugin.ANY_EDGE, MediaSessionPlugin.parseMaxEdge("ANY"));
    }

    @Test
    public void selectPicksSmallestAtOrAboveTarget() {
        List<JSONObject> artwork = Arrays.asList(
                entry("small", "128x128"),
                entry("ideal", "512x512"),
                entry("huge", "1024x1024"));
        assertEquals("ideal", MediaSessionPlugin.selectArtworkSrc(artwork, TARGET));
    }

    @Test
    public void selectPicksLargestWhenNoneReachTarget() {
        List<JSONObject> artwork = Arrays.asList(
                entry("tiny", "96x96"),
                entry("small", "128x128"),
                entry("medium", "256x256"));
        assertEquals("medium", MediaSessionPlugin.selectArtworkSrc(artwork, TARGET));
    }

    @Test
    public void selectPicksLastUsableWhenSizesUnknown() {
        List<JSONObject> artwork = Arrays.asList(
                entry("first", null),
                entry("second", ""),
                entry("third", "garbage"));
        assertEquals("third", MediaSessionPlugin.selectArtworkSrc(artwork, TARGET));
    }

    @Test
    public void selectSkipsEntriesWithNullOrEmptySrc() {
        List<JSONObject> artwork = Arrays.asList(
                entry(null, "512x512"),
                entry("", "512x512"),
                entry("usable", "512x512"));
        assertEquals("usable", MediaSessionPlugin.selectArtworkSrc(artwork, TARGET));
    }

    @Test
    public void selectReturnsNullForEmptyOrNoneUsable() {
        assertNull(MediaSessionPlugin.selectArtworkSrc(new ArrayList<>(), TARGET));
        assertNull(MediaSessionPlugin.selectArtworkSrc(
                Collections.singletonList(entry(null, "512x512")), TARGET));
        assertNull(MediaSessionPlugin.selectArtworkSrc(null, TARGET));
    }

    @Test
    public void selectAnyTiesExactTargetAndLastWins() {
        // "any" resolves to the target edge, tying an exact 512 raster; the later entry wins on a tie.
        List<JSONObject> artwork = Arrays.asList(
                entry("raster", "512x512"),
                entry("vector", "any"));
        assertEquals("vector", MediaSessionPlugin.selectArtworkSrc(artwork, TARGET));

        // Order swapped: the raster now wins the tie (last of the two at edge 512).
        List<JSONObject> swapped = Arrays.asList(
                entry("vector", "any"),
                entry("raster", "512x512"));
        assertEquals("raster", MediaSessionPlugin.selectArtworkSrc(swapped, TARGET));
    }

    @Test
    public void selectTiesResolveToLastEntry() {
        List<JSONObject> artwork = Arrays.asList(
                entry("first512", "512x512"),
                entry("second512", "512x512"));
        assertEquals("second512", MediaSessionPlugin.selectArtworkSrc(artwork, TARGET));
    }
}
