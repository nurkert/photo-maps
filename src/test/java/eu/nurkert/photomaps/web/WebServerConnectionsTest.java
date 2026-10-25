package eu.nurkert.photomaps.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nurkert.photomaps.Settings;
import eu.nurkert.photomaps.map.Palette;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebServerConnectionsTest {

    @TempDir
    Path folder;

    /**
     * Port scanners open connections and never send a request. They must not take the web server away from players:
     * with a fixed pool of four threads, four such connections blocked it for good.
     */
    @Test
    void idleConnectionsDoNotBlockPlayers() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        YamlConfiguration config = new YamlConfiguration();
        config.set("web.port", port);
        config.set("web.bind", "127.0.0.1");
        int[] colors = new int[256];
        Arrays.fill(colors, -1);
        colors[4] = 0;
        WebServer web = new WebServer(
                Logger.getLogger("test"), new Sessions(), new Palette(colors), null, Settings.from(config));
        web.start(Tls.selfSigned(folder.resolve("tls.p12").toFile(), "localhost"));
        List<Socket> idle = new ArrayList<>();
        try {
            // like scanners: finish the TLS handshake, then never send a request
            // more than any machine has CPU cores, which virtual threads on Java 21 would be limited to
            for (int i = 0; i < 32; i++) {
                SSLSocket socket = (SSLSocket) trustingEverything().getSocketFactory().createSocket("127.0.0.1", port);
                socket.startHandshake();
                idle.add(socket);
            }
            Thread.sleep(300);

            HttpClient client = HttpClient.newBuilder()
                    .sslContext(trustingEverything())
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            long start = System.nanoTime();
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/assets/icon.png"))
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(200, response.statusCode());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
        } finally {
            for (Socket socket : idle) {
                socket.close();
            }
            web.stop();
        }
    }

    @Test
    void setsTimeoutsUnlessTheAdminDid() {
        assertEquals("30", System.getProperty("sun.net.httpserver.maxReqTime"));
        assertEquals("60", System.getProperty("sun.net.httpserver.maxRspTime"));
    }

    // the test only checks availability, not the self-signed certificate
    private static SSLContext trustingEverything() throws Exception {
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trustAll}, null);
        return context;
    }
}
