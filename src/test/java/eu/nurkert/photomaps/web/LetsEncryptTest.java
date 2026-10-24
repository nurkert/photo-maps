package eu.nurkert.photomaps.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LetsEncryptTest {

    @TempDir
    Path folder;

    @Test
    void acceptsOnlyDomainNames() {
        assertTrue(LetsEncrypt.isValidDomain("photos.example.com"));
        assertTrue(LetsEncrypt.isValidDomain("a-b.example.de"));
        assertFalse(LetsEncrypt.isValidDomain("localhost"));
        assertFalse(LetsEncrypt.isValidDomain("192.168.1.20"));
        assertFalse(LetsEncrypt.isValidDomain("*.example.com"));
        assertFalse(LetsEncrypt.isValidDomain("../../etc.example.com"));
        assertFalse(LetsEncrypt.isValidDomain("example.com/x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LetsEncrypt(folder.toFile(), "../evil", "", LetsEncrypt.STAGING, "", 80, null));
    }

    @Test
    void hasNoCertificateAtFirst() {
        LetsEncrypt letsEncrypt =
                new LetsEncrypt(folder.toFile(), "photos.example.com", "", LetsEncrypt.STAGING, "", 80, null);
        assertFalse(letsEncrypt.hasValidCertificate());
    }

    @Test
    void renewsWhenAThirdOfTheLifetimeIsLeft() {
        java.time.Instant start = java.time.Instant.parse("2026-01-01T00:00:00Z");
        java.time.Instant end90 = start.plus(java.time.Duration.ofDays(90));
        assertFalse(LetsEncrypt.needsRenewal(start, end90, start.plus(java.time.Duration.ofDays(59))));
        assertTrue(LetsEncrypt.needsRenewal(start, end90, start.plus(java.time.Duration.ofDays(60))));
        // a six-day certificate is fine for four days, not renewed every hour
        java.time.Instant end6 = start.plus(java.time.Duration.ofDays(6));
        assertFalse(LetsEncrypt.needsRenewal(start, end6, start.plus(java.time.Duration.ofDays(3))));
        assertTrue(LetsEncrypt.needsRenewal(start, end6, start.plus(java.time.Duration.ofDays(4))));
        assertTrue(LetsEncrypt.needsRenewal(start, end6, end6.plusSeconds(1)));
    }

    @Test
    void recognisesAStoredCertificate() throws Exception {
        LetsEncrypt letsEncrypt =
                new LetsEncrypt(folder.toFile(), "photos.example.com", "", LetsEncrypt.STAGING, "", 80, null);
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = Tls.createSelfSigned(pair, "photos.example.com");
        Files.createDirectories(letsEncrypt.certificateFile().getParentFile().toPath());
        Files.writeString(letsEncrypt.certificateFile().toPath(), pem("CERTIFICATE", certificate.getEncoded()));
        Files.writeString(letsEncrypt.keyFile().toPath(), pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        assertTrue(letsEncrypt.hasValidCertificate());
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + java.util.Base64.getMimeEncoder().encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    /**
     * Runs against Pebble, Let's Encrypt's test CA, when it is running: PEBBLE_DIRECTORY (its directory URL), PEBBLE_CA
     * (the CA of its HTTPS endpoint) and PEBBLE_CHALLENGE_PORT (its httpPort). CI starts it; locally see
     * docs/development.md.
     */
    @Test
    void getsACertificateFromPebble() throws Exception {
        String directory = System.getenv("PEBBLE_DIRECTORY");
        assumeTrue(directory != null, "Pebble is not running");
        HttpClient http = HttpClient.newBuilder()
                .sslContext(trusting(new File(System.getenv("PEBBLE_CA"))))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        int port = Integer.parseInt(System.getenv().getOrDefault("PEBBLE_CHALLENGE_PORT", "5002"));
        LetsEncrypt letsEncrypt = new LetsEncrypt(
                folder.toFile(), "photos.example.test", "admin@example.test", URI.create(directory), "", port, http);

        letsEncrypt.obtain();

        String stored = Files.readString(letsEncrypt.certificateFile().toPath());
        assertNotNull(letsEncrypt.expiry(), () -> "unreadable certificate: " + stored);
        assertTrue(letsEncrypt.keyFile().isFile(), "no key file");
        assertTrue(letsEncrypt.hasValidCertificate(), () -> "certificate expires " + letsEncrypt.expiry());
        X509Certificate certificate;
        try (InputStream in = Files.newInputStream(letsEncrypt.certificateFile().toPath())) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        assertTrue(Tls.coversHost(certificate, "photos.example.test"));
        assertDoesNotThrow(() -> Tls.fromPem(letsEncrypt.certificateFile(), letsEncrypt.keyFile()));
        // the account is kept for renewals
        assertTrue(new File(folder.toFile(), "account.key").isFile());

        // a renewal with the same account works as well
        letsEncrypt.obtain();
        assertTrue(letsEncrypt.hasValidCertificate());
    }

    private static SSLContext trusting(File ca) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        try (InputStream in = Files.newInputStream(ca.toPath())) {
            store.setCertificateEntry("pebble", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
