package eu.nurkert.photomaps.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import eu.nurkert.photomaps.Settings;
import eu.nurkert.photomaps.map.Palette;
import eu.nurkert.photomaps.map.PictureStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.net.ssl.SSLParameters;

/**
 * The web interface the photo codes point to, served by the JDK's built-in HTTP server.
 *
 * <ul>
 *   <li>{@code GET /s/<token>}: the upload page (the same page for every token, it asks the API about its session)
 *   <li>{@code GET /api/session/<token>}: player, time left, size limits, empty maps and the map palette
 *   <li>{@code POST /api/upload/<token>?columns=&rows=&parts=}: the picture as PNG, exactly 128 pixels per map
 *   <li>{@code GET /pack/<sha1>.zip}: the Java resource pack
 * </ul>
 *
 * <p>The page crops and scales the picture in the browser, so the server only receives what ends up on the maps. The
 * server dithers it again itself instead of trusting the browser's preview.
 *
 * <p>Security: the server speaks HTTPS (TLS 1.2/1.3 only) unless the admin turned it off for a reverse proxy. Pages are
 * only reachable with an unguessable token that expires, addresses that keep guessing tokens are blocked for a while,
 * uploads are size-checked before they are decoded, and every page is locked down by a content security policy.
 * With a self-signed certificate the resource pack is also offered over plain HTTP on a second port, because
 * Minecraft only downloads packs from trusted servers; the pack is public and checked by its SHA-1 hash.
 */
public final class WebServer {

    /** What the web server needs from the game. Both methods are called off the main thread. */
    public interface Backend {

        /**
         * @return the empty maps the player can spend, -1 if uploads cost nothing for them, {@code null} if the
         *     player is offline
         */
        Integer emptyMaps(Sessions.Session session) throws Exception;

        /**
         * Creates the maps of the chosen parts and gives them to the player.
         *
         * @param pixels map colours of the whole picture, {@code columns * 128} pixels wide
         * @param parts indices of the chosen parts, row by row
         */
        Result deliver(Sessions.Session session, int columns, int rows, List<Integer> parts, byte[] pixels)
                throws Exception;
    }

    /**
     * @param error {@code null} on success, else a code the page translates: {@code offline}, {@code maps}
     */
    public record Result(String error, int count, int needed, int available) {

        public static Result ok(int count) {
            return new Result(null, count, 0, 0);
        }

        public static Result failure(String error, int needed, int available) {
            return new Result(error, 0, needed, available);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{16}");
    private static final Pattern PAGE = Pattern.compile("/s/(" + TOKEN.pattern() + ")/?");
    private static final Pattern API = Pattern.compile("/api/(session|upload)/(" + TOKEN.pattern() + ")");
    // a 10x10 picture is 1280x1280 pixels, about 6.5 MB uncompressed; PNG is never bigger than that by much
    private static final int MAX_UPLOAD = 16 * 1024 * 1024;
    // wrong tokens one address may try per minute before it is blocked for that minute
    private static final int MAX_MISSES = 20;
    private static final String CSP = "default-src 'self'; img-src 'self' blob: data:; style-src 'self'; "
            + "font-src 'self'; script-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; "
            + "frame-ancestors 'none'";

    private final Logger logger;
    private final Sessions sessions;
    private final Palette palette;
    private final Backend backend;
    private final Map<String, Asset> assets = new HashMap<>();
    private final String paletteJson;
    private volatile Settings settings;
    private volatile byte[] pack;
    private volatile String packSha1;
    private final Map<InetAddress, int[]> misses = new ConcurrentHashMap<>();
    private volatile long missWindow;
    private HttpServer server;
    private HttpServer packServer;
    private ExecutorService executor;
    private boolean https;

    private record Asset(String type, byte[] content) {}

    public WebServer(Logger logger, Sessions sessions, Palette palette, Backend backend, Settings settings) {
        this.logger = logger;
        this.sessions = sessions;
        this.palette = palette;
        this.backend = backend;
        this.settings = settings;
        this.paletteJson = paletteJson(palette.colors());
        asset("index.html", "text/html; charset=utf-8");
        asset("app.js", "text/javascript; charset=utf-8");
        asset("app.css", "text/css; charset=utf-8");
        asset("icon.png", "image/png");
        for (String sprite : List.of("camera", "selfie", "gallery", "map", "background")) {
            asset(sprite + ".png", "image/png");
        }
        asset("monocraft.ttf", "font/ttf");
        asset("monocraft-LICENSE.txt", "text/plain; charset=utf-8");
    }

    /**
     * @param tls the certificate, {@code null} for plain HTTP
     */
    public void start(Tls tls) throws IOException {
        Settings current = settings;
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "PhotoMaps-Web");
            thread.setDaemon(true);
            return thread;
        });
        https = tls != null;
        if (tls != null) {
            HttpsServer secure = HttpsServer.create(address(current.port()), 0);
            secure.setHttpsConfigurator(new HttpsConfigurator(tls.context()) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters parameters = getSSLContext().getDefaultSSLParameters();
                    parameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                    params.setSSLParameters(parameters);
                }
            });
            server = secure;
        } else {
            server = HttpServer.create(address(current.port()), 0);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        try {
            if (tls != null && tls.isSelfSigned() && current.packPort() > 0 && pack != null) {
                packServer = HttpServer.create(address(current.packPort()), 0);
                packServer.setExecutor(executor);
                packServer.createContext("/", this::handlePack);
                packServer.start();
            }
        } catch (IOException exception) {
            server = null;
            executor.shutdownNow();
            throw exception;
        }
        server.start();
    }

    private InetSocketAddress address(int port) {
        String bind = settings.bind();
        return bind.isBlank() || bind.equals("0.0.0.0") ? new InetSocketAddress(port) : new InetSocketAddress(bind, port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (packServer != null) {
            packServer.stop(0);
            packServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Whether the resource pack is served on its own plain HTTP port. */
    public boolean hasPackServer() {
        return packServer != null;
    }

    /** The second listener of a self-signed setup: only the resource pack, nothing else. */
    private void handlePack(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (pack != null && exchange.getRequestURI().getPath().equals("/pack/" + packSha1 + ".zip")) {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
                send(exchange, 200, "application/zip", pack);
            } else {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    /** Hosts the Java resource pack at {@code /pack/<sha1>.zip}. */
    public void setPack(byte[] pack, String sha1) {
        this.pack = pack;
        this.packSha1 = sha1;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (https) {
                exchange.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000");
            }
            Matcher api = API.matcher(path);
            if (api.matches()) {
                InetAddress client = exchange.getRemoteAddress().getAddress();
                if (isBlocked(client)) {
                    json(exchange, 429, "{\"error\":\"busy\"}");
                    return;
                }
                Sessions.Session session = sessions.get(api.group(2));
                if (session == null) {
                    miss(client);
                }
                if (api.group(1).equals("session") && method.equals("GET")) {
                    sessionInfo(exchange, session);
                } else if (api.group(1).equals("upload") && method.equals("POST")) {
                    upload(exchange, session);
                } else {
                    json(exchange, 405, "{\"error\":\"method\"}");
                }
            } else if (!method.equals("GET") && !method.equals("HEAD")) {
                json(exchange, 405, "{\"error\":\"method\"}");
            } else if (PAGE.matcher(path).matches()) {
                serve(exchange, assets.get("index.html"));
            } else if (path.startsWith("/assets/") && assets.containsKey(path.substring(8))) {
                serve(exchange, assets.get(path.substring(8)));
            } else if (path.equals("/favicon.ico")) {
                serve(exchange, assets.get("icon.png"));
            } else if (pack != null && path.equals("/pack/" + packSha1 + ".zip")) {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
                send(exchange, 200, "application/zip", pack);
            } else if (path.equals("/")) {
                serve(exchange, assets.get("index.html"));
            } else {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Web request failed: " + exchange.getRequestURI(), exception);
        }
    }

    private boolean isBlocked(InetAddress client) {
        int[] count = misses.get(client);
        return count != null && count[0] >= MAX_MISSES && System.currentTimeMillis() - missWindow < 60_000;
    }

    private void miss(InetAddress client) {
        long now = System.currentTimeMillis();
        if (now - missWindow >= 60_000) {
            misses.clear();
            missWindow = now;
        }
        misses.computeIfAbsent(client, key -> new int[1])[0]++;
    }

    private void sessionInfo(HttpExchange exchange, Sessions.Session session) throws IOException {
        if (session == null) {
            json(exchange, 404, "{\"error\":\"expired\"}");
            return;
        }
        Integer maps;
        try {
            maps = backend.emptyMaps(session);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Could not count the empty maps of " + session.playerName(), exception);
            json(exchange, 500, "{\"error\":\"server\"}");
            return;
        }
        if (maps == null) {
            json(exchange, 409, "{\"error\":\"offline\"}");
            return;
        }
        Settings current = settings;
        json(
                exchange,
                200,
                "{\"player\":" + quote(session.playerName()) + ",\"expiresIn\":"
                        + Math.max(0, session.expiresAt() - System.currentTimeMillis()) + ",\"maxColumns\":"
                        + current.maxColumns() + ",\"maxRows\":" + current.maxRows() + ",\"maps\":" + maps
                        + ",\"palette\":" + paletteJson + "}");
    }

    private void upload(HttpExchange exchange, Sessions.Session session) throws IOException {
        if (session == null) {
            json(exchange, 410, "{\"error\":\"expired\"}");
            return;
        }
        if (!session.busy().compareAndSet(false, true)) {
            json(exchange, 409, "{\"error\":\"busy\"}");
            return;
        }
        try {
            Settings current = settings;
            Map<String, String> query = query(exchange.getRequestURI());
            int columns = parseInt(query.get("columns"));
            int rows = parseInt(query.get("rows"));
            List<Integer> parts = parts(query.get("parts"), columns * rows);
            if (columns < 1 || rows < 1 || columns > current.maxColumns() || rows > current.maxRows() || parts == null) {
                json(exchange, 400, "{\"error\":\"invalid\"}");
                return;
            }
            int width = columns * PictureStore.SIZE;
            int height = rows * PictureStore.SIZE;
            BufferedImage image = decode(readBody(exchange.getRequestBody()), width, height);
            if (image == null) {
                json(exchange, 400, "{\"error\":\"invalid\"}");
                return;
            }
            byte[] pixels = palette.dither(image.getRGB(0, 0, width, height, null, 0, width), width, height);
            Result result = backend.deliver(session, columns, rows, parts, pixels);
            if (result.error() == null) {
                json(exchange, 200, "{\"count\":" + result.count() + "}");
            } else {
                json(
                        exchange,
                        409,
                        "{\"error\":" + quote(result.error()) + ",\"needed\":" + result.needed() + ",\"available\":"
                                + result.available() + "}");
            }
        } catch (TooLargeException exception) {
            json(exchange, 413, "{\"error\":\"invalid\"}");
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Upload of " + session.playerName() + " failed", exception);
            json(exchange, 500, "{\"error\":\"server\"}");
        } finally {
            session.busy().set(false);
        }
    }

    /**
     * Decodes the picture only if it has exactly the expected size, which is checked before the pixels are read.
     */
    static BufferedImage decode(byte[] data, int width, int height) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if (reader.getWidth(0) != width || reader.getHeight(0) != height) {
                    return null;
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * @return the distinct indices in ascending order, {@code null} if the list is empty or an index is out of range
     */
    static List<Integer> parts(String value, int count) {
        if (value == null || value.isBlank()) {
            return null;
        }
        TreeSet<Integer> parts = new TreeSet<>();
        for (String part : value.split(",")) {
            int index = parseInt(part.trim());
            if (index < 0 || index >= count) {
                return null;
            }
            parts.add(index);
        }
        return new ArrayList<>(parts);
    }

    private static int parseInt(String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> query = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    query.put(
                            pair.substring(0, equals),
                            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return query;
    }

    private static final class TooLargeException extends IOException {}

    private static byte[] readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_UPLOAD) {
                throw new TooLargeException();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private void serve(HttpExchange exchange, Asset asset) throws IOException {
        if (asset == null) {
            send(exchange, 404, "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        // the font and the sprites never change within one plugin version
        exchange.getResponseHeaders()
                .set("Cache-Control", asset.type().startsWith("text/") ? "no-cache" : "public, max-age=86400");
        if (asset.type().startsWith("text/html")) {
            exchange.getResponseHeaders().set("Content-Security-Policy", CSP);
        }
        send(exchange, 200, asset.type(), asset.content());
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        boolean head = exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(status, head ? -1 : body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private void asset(String name, String type) {
        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing web/" + name + " in the plugin jar");
            }
            assets.put(name, new Asset(type, in.readAllBytes()));
        } catch (IOException exception) {
            throw new IllegalStateException("could not read web/" + name, exception);
        }
    }

    static String paletteJson(int[] colors) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < colors.length; i++) {
            json.append(i == 0 ? "" : ",").append(colors[i]);
        }
        return json.append(']').toString();
    }

    static String quote(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
