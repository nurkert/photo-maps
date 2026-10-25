package eu.nurkert.photomaps.web;

import java.util.Map;

/**
 * Timeouts for the JDK's HTTP server, which has none by default: a connection that opens and never sends a request
 * (port scanners do this all the time on public ports) would be kept forever. These settings close a request that is
 * not complete after 30 seconds and a response that is not sent after 60.
 *
 * <p>The JDK reads them once, when its first HTTP server starts, so they are set before that, and only where the admin
 * has not set them with {@code -D} options already. They apply to every JDK HTTP server in the JVM.
 */
final class HttpLimits {

    static final Map<String, String> DEFAULTS = Map.of(
            "sun.net.httpserver.maxReqTime", "30",
            "sun.net.httpserver.maxRspTime", "60");

    private HttpLimits() {}

    static void apply() {
        DEFAULTS.forEach((key, value) -> {
            if (System.getProperty(key) == null) {
                System.setProperty(key, value);
            }
        });
    }
}
