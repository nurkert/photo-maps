package eu.nurkert.photomaps.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsTest {

    @TempDir
    Path folder;

    @Test
    void selfSignedCertificateIsValidForTheHost() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = Tls.createSelfSigned(pair, "192.168.1.20");
        certificate.checkValidity();
        certificate.verify(pair.getPublic());
        assertTrue(Tls.coversHost(certificate, "192.168.1.20"));
        assertTrue(Tls.coversHost(certificate, "localhost"));
        assertFalse(Tls.coversHost(certificate, "10.0.0.1"));
        assertTrue(Tls.createSelfSigned(pair, "photos.example.com").getSubjectAlternativeNames().stream()
                .anyMatch(name -> name.get(1).equals("photos.example.com")));
    }

    @Test
    void selfSignedCertificateIsKeptUntilTheHostChanges() throws Exception {
        File file = folder.resolve("tls/self-signed.p12").toFile();
        Tls first = Tls.selfSigned(file, "10.0.0.3");
        assertTrue(first.isSelfSigned());
        assertEquals(first.fingerprint(), Tls.selfSigned(file, "10.0.0.3").fingerprint());
        assertFalse(first.fingerprint().equals(Tls.selfSigned(file, "10.0.0.4").fingerprint()));
    }

    @Test
    void readsPkcs8AndPkcs1Keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PrivateKey key = generator.generateKeyPair().getPrivate();
        byte[] pkcs8 = key.getEncoded();
        assertEquals(key, Tls.readPrivateKey(pem("PRIVATE KEY", pkcs8)));

        // the PKCS#1 key is the octet string at the end of the PKCS#8 structure
        int length = pkcs8.length;
        byte[] pkcs1 = null;
        for (int i = 0; i < length - 4; i++) {
            if (pkcs8[i] == 0x04 && (pkcs8[i + 1] & 0xFF) == 0x82) {
                int size = ((pkcs8[i + 2] & 0xFF) << 8) | (pkcs8[i + 3] & 0xFF);
                if (i + 4 + size == length) {
                    pkcs1 = java.util.Arrays.copyOfRange(pkcs8, i + 4, length);
                    break;
                }
            }
        }
        assertEquals(key, Tls.readPrivateKey(pem("RSA PRIVATE KEY", pkcs1)));
    }

    @Test
    void loadsPemFiles() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = Tls.createSelfSigned(pair, "photos.example.com");
        Path cert = folder.resolve("fullchain.pem");
        Path key = folder.resolve("privkey.pem");
        Files.writeString(cert, pem("CERTIFICATE", certificate.getEncoded()));
        Files.writeString(key, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Tls tls = Tls.fromPem(cert.toFile(), key.toFile());
        assertFalse(tls.isSelfSigned());
        assertFalse(tls.filesChanged());
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type
                + "-----\n";
    }
}
