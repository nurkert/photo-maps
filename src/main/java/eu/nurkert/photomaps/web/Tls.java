package eu.nurkert.photomaps.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The certificate of the web server.
 *
 * <p>Either the admin's own certificate (PEM files, e.g. from Let's Encrypt, or a PKCS#12 key store) or, without one, a
 * self-signed certificate the plugin creates for the server's address. A self-signed certificate encrypts just as
 * well, but phones cannot verify it and show a warning once; a real certificate for a domain avoids that.
 */
public final class Tls {

    private static final String ALIAS = "photomaps";
    private static final char[] SELF_SIGNED_PASSWORD = "photomaps".toCharArray();
    // browsers do not accept server certificates valid for longer than this
    private static final long VALIDITY_DAYS = 397;

    private final SSLContext context;
    private final boolean selfSigned;
    private final String fingerprint;
    private final List<File> watched;
    private final long loadedAt;

    private Tls(SSLContext context, boolean selfSigned, X509Certificate certificate, List<File> watched)
            throws GeneralSecurityException {
        this.context = context;
        this.selfSigned = selfSigned;
        this.fingerprint = HexFormat.ofDelimiter(":")
                .withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        this.watched = watched;
        this.loadedAt = System.currentTimeMillis();
    }

    public SSLContext context() {
        return context;
    }

    public boolean isSelfSigned() {
        return selfSigned;
    }

    /** SHA-256 fingerprint, to compare with what the browser shows. */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * @return whether a certificate file was replaced since it was loaded (Let's Encrypt renews every few weeks)
     */
    public boolean filesChanged() {
        return watched.stream().anyMatch(file -> file.lastModified() > loadedAt);
    }

    /** Loads a certificate chain and its private key from PEM files. */
    public static Tls fromPem(File certificateFile, File keyFile) throws IOException, GeneralSecurityException {
        Collection<? extends Certificate> chain;
        try (InputStream in = new FileInputStream(certificateFile)) {
            chain = CertificateFactory.getInstance("X.509").generateCertificates(in);
        }
        if (chain.isEmpty()) {
            throw new GeneralSecurityException("no certificate in " + certificateFile);
        }
        PrivateKey key = readPrivateKey(Files.readString(keyFile.toPath(), StandardCharsets.US_ASCII));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        char[] password = new char[0];
        store.setKeyEntry(ALIAS, key, password, chain.toArray(Certificate[]::new));
        return new Tls(
                context(store, password),
                false,
                (X509Certificate) chain.iterator().next(),
                List.of(certificateFile, keyFile));
    }

    /** Loads a PKCS#12 key store with one key and its certificate chain. */
    public static Tls fromKeyStore(File file, String password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(file)) {
            store.load(in, password.toCharArray());
        }
        String alias = null;
        for (String candidate : Collections.list(store.aliases())) {
            if (store.isKeyEntry(candidate)) {
                alias = candidate;
                break;
            }
        }
        if (alias == null) {
            throw new GeneralSecurityException("no private key in " + file);
        }
        return new Tls(
                context(store, password.toCharArray()),
                false,
                (X509Certificate) store.getCertificate(alias),
                List.of(file));
    }

    /**
     * Uses the self-signed certificate in {@code file}, or creates a new one if there is none yet, it expires soon or
     * it is not valid for {@code host}.
     */
    public static Tls selfSigned(File file, String host) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        X509Certificate certificate = null;
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                store.load(in, SELF_SIGNED_PASSWORD);
                certificate = (X509Certificate) store.getCertificate(ALIAS);
            } catch (IOException | GeneralSecurityException exception) {
                certificate = null;
            }
        }
        long renewAfter = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000;
        if (certificate == null
                || certificate.getNotAfter().getTime() < renewAfter
                || !coversHost(certificate, host)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            certificate = createSelfSigned(pair, host);
            store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry(ALIAS, pair.getPrivate(), SELF_SIGNED_PASSWORD, new Certificate[] {certificate});
            Files.createDirectories(file.getParentFile().toPath());
            try (OutputStream out = new FileOutputStream(file)) {
                store.store(out, SELF_SIGNED_PASSWORD);
            }
        }
        return new Tls(context(store, SELF_SIGNED_PASSWORD), true, certificate, List.of());
    }

    private static SSLContext context(KeyStore store, char[] password) throws GeneralSecurityException {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    static boolean coversHost(X509Certificate certificate, String host) {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            return names != null && names.stream().anyMatch(name -> host.equalsIgnoreCase(String.valueOf(name.get(1))));
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------- PEM keys

    private static final Pattern PEM = Pattern.compile("-----BEGIN ([A-Z ]+)-----([^-]+)-----END \\1-----");

    /** Reads a PKCS#8 key ({@code BEGIN PRIVATE KEY}, RSA or EC) or a PKCS#1 RSA key ({@code BEGIN RSA PRIVATE KEY}). */
    static PrivateKey readPrivateKey(String pem) throws GeneralSecurityException {
        Matcher matcher = PEM.matcher(pem);
        while (matcher.find()) {
            String type = matcher.group(1);
            byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
            if (type.equals("RSA PRIVATE KEY")) {
                // wrap PKCS#1 into PKCS#8: version 0, rsaEncryption, the key as octet string
                der = seq(integer(BigInteger.ZERO), seq(oid("1.2.840.113549.1.1.1"), tlv(0x05)), tlv(0x04, der));
                type = "PRIVATE KEY";
            }
            if (type.equals("PRIVATE KEY")) {
                for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
                    try {
                        return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
                    } catch (GeneralSecurityException ignored) {
                        // try the next algorithm
                    }
                }
            }
            if (type.equals("EC PRIVATE KEY")) {
                throw new GeneralSecurityException("EC keys in the old format are not supported, convert it with: "
                        + "openssl pkcs8 -topk8 -nocrypt -in privkey.pem -out privkey-pkcs8.pem");
            }
        }
        throw new GeneralSecurityException("no unencrypted private key found");
    }

    // ------------------------------------------------------------------------------------- self-signed certificate

    static X509Certificate createSelfSigned(KeyPair pair, String host) throws GeneralSecurityException {
        byte[] name = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, "PhotoMaps".getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(time(new Date(now - 60_000)), time(new Date(now + VALIDITY_DAYS * 24 * 60 * 60 * 1000)));
        byte[] algorithm = seq(oid("1.2.840.10045.4.3.2")); // ecdsa-with-SHA256

        List<byte[]> alternativeNames = new ArrayList<>();
        alternativeNames.add(sanEntry(host));
        if (!host.equals("localhost")) {
            alternativeNames.add(tlv(0x82, "localhost".getBytes(StandardCharsets.US_ASCII)));
        }
        byte[] extensions = tlv(
                0xA3,
                seq(
                        seq(oid("2.5.29.17"), tlv(0x04, seq(alternativeNames.toArray(byte[][]::new)))),
                        // extended key usage: TLS server
                        seq(oid("2.5.29.37"), tlv(0x04, seq(oid("1.3.6.1.5.5.7.3.1"))))));
        byte[] serial = integer(new BigInteger(64, new SecureRandom()).setBit(62));
        byte[] tbs = seq(
                tlv(0xA0, integer(BigInteger.TWO)),
                serial,
                algorithm,
                name,
                validity,
                name,
                pair.getPublic().getEncoded(),
                extensions);

        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate());
        signature.update(tbs);
        byte[] signed = signature.sign();
        byte[] bits = new byte[signed.length + 1];
        System.arraycopy(signed, 0, bits, 1, signed.length);
        byte[] certificate = seq(tbs, algorithm, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate));
    }

    private static byte[] sanEntry(String host) {
        if (host.matches("[0-9.]+") || host.contains(":")) {
            try {
                return tlv(0x87, InetAddress.getByName(host).getAddress());
            } catch (IOException ignored) {
                // not an address after all
            }
        }
        return tlv(0x82, host.getBytes(StandardCharsets.US_ASCII));
    }

    // a minimal DER writer, enough for one certificate

    private static byte[] time(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    private static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long value = Long.parseLong(parts[i]);
            int shift = 63 - Long.numberOfLeadingZeros(value | 1);
            for (int group = shift / 7; group > 0; group--) {
                out.write((int) (0x80 | (value >> (7 * group)) & 0x7F));
            }
            out.write((int) (value & 0x7F));
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, parts);
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, parts);
    }

    static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            content.writeBytes(part);
        }
        int length = content.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = (32 - Integer.numberOfLeadingZeros(length) + 7) / 8;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write(length >> (8 * i));
            }
        }
        out.writeBytes(content.toByteArray());
        return out.toByteArray();
    }
}
