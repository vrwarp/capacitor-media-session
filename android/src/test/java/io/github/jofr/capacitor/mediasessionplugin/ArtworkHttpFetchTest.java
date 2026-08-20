package io.github.jofr.capacitor.mediasessionplugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-socket tests for {@link MediaSessionPlugin#httpToArtworkData(String)} against a minimal
 * hand-rolled loopback HTTP server ({@code java.net.ServerSocket} is part of the Android API, so
 * this compiles against {@code android.jar} and adds NO test dependency). These exercise the actual
 * redirect/size-cap fetch loop that the injected-fetcher plugin tests deliberately bypass:
 * terminal 200 decode, relative/absolute redirect chains, redirect loops, the hop cap, the
 * Content-Length fast-fail and the streaming byte cap.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ArtworkHttpFetchTest {

    /** One canned HTTP response: status, optional Location, optional body/Content-Length. */
    private static final class Response {
        final int code;
        final String location;
        final byte[] body;
        /** Content-Length header value; {@code null} = derive from body, {@code -1} = omit entirely. */
        final Long contentLength;
        /** When > 0, stream this many zero bytes with NO Content-Length (close-delimited). */
        final long streamBytes;

        Response(int code, String location, byte[] body, Long contentLength, long streamBytes) {
            this.code = code;
            this.location = location;
            this.body = body;
            this.contentLength = contentLength;
            this.streamBytes = streamBytes;
        }

        static Response ok(byte[] body) {
            return new Response(200, null, body, null, 0);
        }

        static Response redirect(int code, String location) {
            return new Response(code, location, null, null, 0);
        }

        static Response status(int code) {
            return new Response(code, null, null, null, 0);
        }
    }

    /**
     * Minimal single-threaded HTTP/1.1 server: every response carries {@code Connection: close}, so
     * each request is one connection and the accept loop can serve them serially. Handles exactly
     * what {@code HttpURLConnection} sends for a GET.
     */
    private static final class LoopbackHttpServer implements Closeable {
        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private final Map<String, Response> routes = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        private volatile boolean closed = false;

        LoopbackHttpServer() throws IOException {
            serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            acceptThread = new Thread(this::acceptLoop, "loopback-http-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + serverSocket.getLocalPort();
        }

        void route(String path, Response response) {
            routes.put(path, response);
        }

        int hits(String path) {
            AtomicInteger counter = hits.get(path);
            return counter == null ? 0 : counter.get();
        }

        private void acceptLoop() {
            while (!closed) {
                try (Socket socket = serverSocket.accept()) {
                    handle(socket);
                } catch (IOException e) {
                    if (closed) {
                        return;
                    }
                    // A client aborting mid-response (expected in the cap tests) must not kill
                    // the accept loop for subsequent requests.
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            socket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            // Drain the request headers so the client never blocks on an unread request body.
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // ignore headers
            }
            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            Response response = routes.get(path);
            if (response == null) {
                response = Response.status(404);
            }

            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(response.code).append(" X\r\n");
            head.append("Connection: close\r\n");
            if (response.location != null) {
                head.append("Location: ").append(response.location).append("\r\n");
            }
            if (response.streamBytes > 0) {
                // close-delimited body: intentionally no Content-Length
            } else if (response.contentLength == null) {
                head.append("Content-Length: ")
                        .append(response.body == null ? 0 : response.body.length)
                        .append("\r\n");
            } else if (response.contentLength >= 0) {
                head.append("Content-Length: ").append(response.contentLength).append("\r\n");
            }
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            if (response.body != null) {
                out.write(response.body);
            }
            if (response.streamBytes > 0) {
                byte[] chunk = new byte[64 * 1024];
                long written = 0;
                while (written < response.streamBytes) {
                    out.write(chunk, 0, (int) Math.min(chunk.length, response.streamBytes - written));
                    written += chunk.length;
                }
            }
            out.flush();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            serverSocket.close();
        }
    }

    private MediaSessionPlugin plugin;
    private LoopbackHttpServer server;
    private String base;

    @Before
    public void setUp() throws IOException {
        plugin = new MediaSessionPlugin();
        server = new LoopbackHttpServer();
        base = server.baseUrl();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
    }

    private static byte[] pngBytes(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF336699);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        return stream.toByteArray();
    }

    @Test
    public void fetchesAndDecodesATerminal200() throws IOException {
        server.route("/cover.png", Response.ok(pngBytes(64, 64)));

        byte[] data = plugin.httpToArtworkData(base + "/cover.png");

        assertNotNull(data);
        Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length);
        assertEquals(64, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
    }

    @Test
    public void oversizedImageIsDownsampledToTheArtworkCap() throws IOException {
        server.route("/big.png", Response.ok(pngBytes(1024, 768)));

        byte[] data = plugin.httpToArtworkData(base + "/big.png");

        assertNotNull(data);
        Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length);
        assertEquals(512, decoded.getWidth());
        assertEquals(384, decoded.getHeight());
    }

    @Test
    public void followsRelativeAndAbsoluteRedirectChains() throws IOException {
        server.route("/start", Response.redirect(302, "/hop")); // relative absolute-path Location
        server.route("/hop", Response.redirect(301, base + "/cover.png")); // absolute Location
        server.route("/cover.png", Response.ok(pngBytes(32, 32)));

        byte[] data = plugin.httpToArtworkData(base + "/start");

        assertNotNull(data);
        assertEquals(32, BitmapFactory.decodeByteArray(data, 0, data.length).getWidth());
    }

    @Test
    public void succeedsAtExactlyTheRedirectHopCap() throws IOException {
        // 5 redirects then the image: hop 5 (the last allowed iteration) reads the terminal 200.
        for (int i = 0; i < 5; i++) {
            server.route("/r" + i, Response.redirect(302, "/r" + (i + 1)));
        }
        server.route("/r5", Response.ok(pngBytes(16, 16)));

        assertNotNull(plugin.httpToArtworkData(base + "/r0"));
    }

    @Test
    public void abortsBeyondTheRedirectHopCap() throws IOException {
        // 6 redirects before the image: one hop past the cap, the loop must give up.
        for (int i = 0; i < 6; i++) {
            server.route("/s" + i, Response.redirect(302, "/s" + (i + 1)));
        }
        server.route("/s6", Response.ok(pngBytes(16, 16)));

        assertNull(plugin.httpToArtworkData(base + "/s0"));
        assertEquals(0, server.hits("/s6"));
    }

    @Test
    public void abortsOnARedirectLoopBeforeTheHopCap() throws IOException {
        server.route("/loopA", Response.redirect(302, "/loopB"));
        server.route("/loopB", Response.redirect(302, "/loopA"));

        assertNull(plugin.httpToArtworkData(base + "/loopA"));
        // The visited-set guard must catch the cycle on its first revisit, not re-fetch /loopA.
        assertEquals(1, server.hits("/loopA"));
        assertEquals(1, server.hits("/loopB"));
    }

    @Test
    public void non200TerminalResponseReturnsNull() throws IOException {
        server.route("/missing.png", Response.status(404));

        assertNull(plugin.httpToArtworkData(base + "/missing.png"));
    }

    @Test
    public void redirectToAnUnsupportedSchemeStops() throws IOException {
        server.route("/evil", Response.redirect(302, "file:///etc/passwd"));

        // The scheme guard refuses to follow; the 302 itself is then a non-200 terminal response.
        assertNull(plugin.httpToArtworkData(base + "/evil"));
    }

    @Test
    public void advertisedContentLengthOverTheCapFastFails() throws IOException {
        // Advertise an over-cap body while sending only a token amount: the client must abort on
        // the header alone, never attempting to read the rest.
        server.route("/huge.png", new Response(
                200, null, new byte[1024], (long) MediaSessionPlugin.MAX_ARTWORK_BYTES + 1, 0));

        assertNull(plugin.httpToArtworkData(base + "/huge.png"));
    }

    @Test
    public void streamedBodyOverTheCapIsAborted() throws IOException {
        // No Content-Length (close-delimited): the fast-fail cannot trigger, so the streaming
        // byte cap has to abort the read mid-body.
        server.route("/stream.png", new Response(
                200, null, null, null, (long) MediaSessionPlugin.MAX_ARTWORK_BYTES + 128 * 1024));

        assertNull(plugin.httpToArtworkData(base + "/stream.png"));
    }

    @Test
    public void unreachableServerFailsWithIOExceptionNotUnchecked() throws IOException {
        server.close();

        try {
            // A refused connection surfaces as IOException (or a null after a terminal failure);
            // setMetadata's fetch wrapper catches exactly that and maps it to
            // artworkload {loaded:false}. Anything unchecked would escape and crash the executor.
            assertNull(plugin.httpToArtworkData(base + "/cover.png"));
        } catch (IOException expected) {
            assertTrue(true);
        }
    }
}
