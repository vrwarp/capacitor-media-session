package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.util.Base64;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;

/**
 * Unit tests for {@link MediaSessionPlugin#decodeDataUri(String)} — the RFC 2397 {@code data:} URI
 * parser that feeds the artwork pipeline. Covers base64 vs percent-encoded bodies, malformed input,
 * and the token-precise {@code ;base64} flag detection.
 *
 * <p>Runs under Robolectric (like {@link ArtworkSelectionTest}) because {@code decodeDataUri} uses
 * {@code android.util.Base64}, which the bare unit-test classpath stubs to throw "not mocked". The
 * parsing logic under test is otherwise pure.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ArtworkDataUriTest {
    // 1x1 transparent PNG, standard base64.
    private static final String PNG_1X1_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Test
    public void decodesValidBase64Body() {
        byte[] decoded = MediaSessionPlugin.decodeDataUri("data:image/png;base64," + PNG_1X1_BASE64);

        assertNotNull(decoded);
        // The parser must extract exactly the post-comma body and decode it with the standard alphabet.
        assertArrayEquals(Base64.decode(PNG_1X1_BASE64, Base64.DEFAULT), decoded);
    }

    @Test
    public void decodesPercentEncodedBody() {
        byte[] decoded = MediaSessionPlugin.decodeDataUri("data:text/plain,hello%20world");

        assertNotNull(decoded);
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), decoded);
    }

    @Test
    public void decodesPlainBodyWithoutMediatype() {
        byte[] decoded = MediaSessionPlugin.decodeDataUri("data:,plainfoo");

        assertNotNull(decoded);
        assertArrayEquals("plainfoo".getBytes(StandardCharsets.UTF_8), decoded);
    }

    @Test
    public void malformedBase64ReturnsNull() {
        // '!' is outside the standard base64 alphabet -> decode rejects -> null (no exception escapes).
        assertNull(MediaSessionPlugin.decodeDataUri("data:image/png;base64,!!!not-base64!!!"));
    }

    @Test
    public void missingCommaReturnsNull() {
        assertNull(MediaSessionPlugin.decodeDataUri("data:image/png;base64"));
    }

    @Test
    public void nonDataSchemeReturnsNull() {
        assertNull(MediaSessionPlugin.decodeDataUri("http://example.com/cover.png"));
        assertNull(MediaSessionPlugin.decodeDataUri("blob:abc-123"));
        assertNull(MediaSessionPlugin.decodeDataUri(null));
    }

    @Test
    public void base64FlagIsTokenMatchedNotSubstring() {
        // ";charset=base64x" must NOT be treated as the base64 flag: the body is percent-decoded, so a
        // value that would be garbage as base64 round-trips verbatim as text.
        byte[] decoded = MediaSessionPlugin.decodeDataUri("data:text/plain;charset=base64x,hi");

        assertNotNull(decoded);
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), decoded);
    }
}
