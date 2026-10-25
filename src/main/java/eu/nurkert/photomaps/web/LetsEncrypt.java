package eu.nurkert.photomaps.web;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gets and renews a certificate for the server's domain from Let's Encrypt, so admins only have to enter the domain.
 *
 * <p>Let's Encrypt checks that the server really answers for the domain by fetching a token from
 * {@code http://<domain>/.well-known/acme-challenge/} on port 80. A small HTTP server answers there only while a
 * certificate is being issued and serves nothing else. Files live in {@code plugins/PhotoMaps/tls/lets-encrypt/}.
 */
public final class LetsEncrypt {

    static {
        HttpLimits.apply();
    }

    public static final URI PRODUCTION = URI.create("https://acme-v02.api.letsencrypt.org/directory");
    public static final URI STAGING = URI.create("https://acme-staging-v02.api.letsencrypt.org/directory");

    private final File folder;
    private final String domain;
    private final String email;
    private final URI directory;
    private final String bind;
    private final int challengePort;
    private final HttpClient http;

    public LetsEncrypt(
            File folder, String domain, String email, URI directory, String bind, int challengePort, HttpClient http) {
        this.folder = folder;
        this.domain = domain.trim().toLowerCase(Locale.ROOT);
        if (!isValidDomain(this.domain)) {
            throw new IllegalArgumentException("not a domain name: " + domain);
        }
        this.email = email;
        this.directory = directory;
        this.bind = bind;
        this.challengePort = challengePort;
        this.http = http;
    }

    /** A public DNS name like photos.example.com: no IP address, no wildcard, no path. */
    public static boolean isValidDomain(String domain) {
        return domain.length() <= 253
                && domain.matches("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?");
    }

    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String domain() {
        return domain;
    }

    public File certificateFile() {
        return new File(folder, domain + "/fullchain.pem");
    }

    public File keyFile() {
        return new File(folder, domain + "/privkey.pem");
    }

    /**
     * @return whether a certificate for the domain exists and does not need to be renewed yet
     */
    public boolean hasValidCertificate() {
        X509Certificate certificate = certificate();
        return certificate != null
                && !needsRenewal(
                        certificate.getNotBefore().toInstant(), certificate.getNotAfter().toInstant(), Instant.now());
    }

    /**
     * Renews once less than a third of the lifetime is left, as Let's Encrypt recommends: 30 days before a 90-day
     * certificate expires, 2 days before a 6-day one. A fixed number of days would renew short-lived certificates
     * all the time.
     */
    static boolean needsRenewal(Instant notBefore, Instant notAfter, Instant now) {
        Duration lifetime = Duration.between(notBefore, notAfter);
        return !now.isBefore(notAfter.minus(lifetime.dividedBy(3)));
    }

    /**
     * @return when the current certificate expires, {@code null} without one
     */
    public Date expiry() {
        X509Certificate certificate = certificate();
        return certificate != null ? certificate.getNotAfter() : null;
    }

    private X509Certificate certificate() {
        File file = certificateFile();
        if (!file.isFile() || !keyFile().isFile()) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (IOException | GeneralSecurityException exception) {
            return null;
        }
    }

    /**
     * Gets a new certificate and stores it. Blocks for a few seconds up to a minute; never call it on the main thread.
     *
     * @throws BindException if the challenge port cannot be opened
     */
    public void obtain() throws IOException, InterruptedException, GeneralSecurityException {
        Map<String, String> tokens = new ConcurrentHashMap<>();
        HttpServer challenges = startChallengeServer(tokens);
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair certificateKey = generator.generateKeyPair();
            AcmeClient client = new AcmeClient(directory, http, accountKey());
            String chain = client.order(domain, email, certificateKey, tokens::put, tokens::remove);
            if (!chain.contains("BEGIN CERTIFICATE")) {
                throw new IOException("the CA sent no certificate");
            }
            Path target = certificateFile().getParentFile().toPath();
            Files.createDirectories(target);
            writePrivate(keyFile().toPath(), pem("PRIVATE KEY", certificateKey.getPrivate().getEncoded()));
            writeAtomically(certificateFile().toPath(), chain);
        } finally {
            challenges.stop(0);
        }
    }

    private HttpServer startChallengeServer(Map<String, String> tokens) throws IOException {
        InetSocketAddress address = bind.isBlank() || bind.equals("0.0.0.0")
                ? new InetSocketAddress(challengePort)
                : new InetSocketAddress(bind, challengePort);
        HttpServer server = HttpServer.create(address, 0);
        server.createContext("/.well-known/acme-challenge/", exchange -> {
            try (exchange) {
                String token = exchange.getRequestURI().getPath().substring("/.well-known/acme-challenge/".length());
                String answer = tokens.get(token);
                byte[] body = answer != null ? answer.getBytes(StandardCharsets.US_ASCII) : new byte[0];
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(answer != null ? 200 : 404, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            }
        });
        server.start();
        return server;
    }

    /** The ACME account key, created once and kept, so renewals use the same account. */
    private KeyPair accountKey() throws IOException, GeneralSecurityException {
        Path privateFile = new File(folder, "account.key").toPath();
        Path publicFile = new File(folder, "account.pub").toPath();
        KeyFactory factory = KeyFactory.getInstance("EC");
        if (Files.isRegularFile(privateFile) && Files.isRegularFile(publicFile)) {
            return new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicFile))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile))));
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        Files.createDirectories(folder.toPath());
        writePrivate(privateFile, pair.getPrivate().getEncoded());
        Files.write(publicFile, pair.getPublic().getEncoded());
        return pair;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    private static void writePrivate(Path path, String text) throws IOException {
        writePrivate(path, text.getBytes(StandardCharsets.US_ASCII));
    }

    /** Writes a private key readable only by the server's user (where the file system supports it). */
    private static void writePrivate(Path path, byte[] content) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        try {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException exception) {
            Files.createFile(temp); // Windows
        }
        Files.write(temp, content);
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeAtomically(Path path, String content) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.US_ASCII);
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
