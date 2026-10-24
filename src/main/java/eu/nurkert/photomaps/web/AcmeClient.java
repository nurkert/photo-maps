package eu.nurkert.photomaps.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A small ACME client (RFC 8555) for one domain and the HTTP-01 challenge: enough to get a certificate from Let's
 * Encrypt without any library.
 *
 * <p>The account and the certificate use P-256 keys. Every request is a JWS signed with the account key; nonces come
 * from the server and a rejected nonce is retried, as the protocol asks.
 */
public final class AcmeClient {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final int MAX_POLLS = 60;

    private final URI directoryUrl;
    private final HttpClient http;
    private final KeyPair account;
    private JsonObject directory;
    private String nonce;
    private String accountUrl;

    public AcmeClient(URI directoryUrl, HttpClient http, KeyPair account) {
        this.directoryUrl = directoryUrl;
        this.http = http;
        this.account = account;
    }

    /**
     * Orders a certificate for {@code domain}.
     *
     * @param publish makes {@code /.well-known/acme-challenge/<token>} answer with the key authorization
     * @param unpublish removes it again
     * @return the certificate chain as PEM, the certificate first
     */
    public String order(
            String domain,
            String email,
            KeyPair certificateKey,
            BiConsumer<String, String> publish,
            Consumer<String> unpublish)
            throws IOException, InterruptedException, GeneralSecurityException {
        loadDirectory();
        register(email);

        JsonObject identifier = new JsonObject();
        identifier.addProperty("type", "dns");
        identifier.addProperty("value", domain);
        JsonArray identifiers = new JsonArray();
        identifiers.add(identifier);
        JsonObject newOrder = new JsonObject();
        newOrder.add("identifiers", identifiers);
        HttpResponse<String> created = post(url("newOrder"), newOrder.toString());
        String orderUrl = created.headers().firstValue("Location").orElseThrow(() -> new IOException("order without location"));
        JsonObject order = json(created);

        for (JsonElement authorizationUrl : order.getAsJsonArray("authorizations")) {
            authorize(authorizationUrl.getAsString(), publish, unpublish);
        }

        JsonObject finalize = new JsonObject();
        finalize.addProperty("csr", B64.encodeToString(csr(domain, certificateKey)));
        post(order.get("finalize").getAsString(), finalize.toString());
        order = poll(orderUrl, "valid", "invalid");
        if (!order.get("status").getAsString().equals("valid")) {
            throw new IOException("the order failed: " + order);
        }

        HttpResponse<String> certificate = post(order.get("certificate").getAsString(), null);
        return certificate.body();
    }

    private void authorize(String url, BiConsumer<String, String> publish, Consumer<String> unpublish)
            throws IOException, InterruptedException, GeneralSecurityException {
        JsonObject authorization = json(post(url, null));
        if (authorization.get("status").getAsString().equals("valid")) {
            return; // validated recently, Let's Encrypt remembers that for a while
        }
        JsonObject challenge = null;
        for (JsonElement element : authorization.getAsJsonArray("challenges")) {
            if (element.getAsJsonObject().get("type").getAsString().equals("http-01")) {
                challenge = element.getAsJsonObject();
            }
        }
        if (challenge == null) {
            throw new IOException("the CA offers no http-01 challenge");
        }
        String token = challenge.get("token").getAsString();
        if (!token.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("unexpected challenge token");
        }
        publish.accept(token, token + "." + thumbprint());
        try {
            post(challenge.get("url").getAsString(), "{}");
            JsonObject result = poll(url, "valid", "invalid", "deactivated", "expired", "revoked");
            if (!result.get("status").getAsString().equals("valid")) {
                throw new IOException("the domain could not be validated: " + problem(result));
            }
        } finally {
            unpublish.accept(token);
        }
    }

    private static String problem(JsonObject authorization) {
        for (JsonElement element : authorization.getAsJsonArray("challenges")) {
            JsonObject challenge = element.getAsJsonObject();
            if (challenge.has("error")) {
                JsonObject error = challenge.getAsJsonObject("error");
                return error.has("detail") ? error.get("detail").getAsString() : error.toString();
            }
        }
        return authorization.get("status").getAsString();
    }

    private JsonObject poll(String url, String... finalStates)
            throws IOException, InterruptedException, GeneralSecurityException {
        for (int attempt = 0; attempt < MAX_POLLS; attempt++) {
            HttpResponse<String> response = post(url, null);
            JsonObject body = json(response);
            String status = body.get("status").getAsString();
            for (String state : finalStates) {
                if (state.equals(status)) {
                    return body;
                }
            }
            long wait = response.headers()
                    .firstValue("Retry-After")
                    .map(AcmeClient::seconds)
                    .orElse(2L);
            Thread.sleep(Math.max(1, Math.min(wait, 10)) * 1000);
        }
        throw new IOException("the CA did not finish in time: " + url);
    }

    private static long seconds(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return 2;
        }
    }

    private void register(String email) throws IOException, InterruptedException, GeneralSecurityException {
        if (accountUrl != null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("termsOfServiceAgreed", true);
        if (email != null && !email.isBlank()) {
            JsonArray contact = new JsonArray();
            contact.add("mailto:" + email.trim());
            payload.add("contact", contact);
        }
        HttpResponse<String> response = post(url("newAccount"), payload.toString());
        accountUrl = response.headers().firstValue("Location").orElseThrow(() -> new IOException("account without location"));
    }

    private void loadDirectory() throws IOException, InterruptedException {
        if (directory == null) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(directoryUrl).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check(response);
            directory = JsonParser.parseString(response.body()).getAsJsonObject();
        }
    }

    private String url(String name) throws IOException {
        if (!directory.has(name)) {
            throw new IOException("the ACME directory has no " + name);
        }
        return directory.get(name).getAsString();
    }

    private String freshNonce() throws IOException, InterruptedException {
        if (nonce != null) {
            String current = nonce;
            nonce = null;
            return current;
        }
        HttpResponse<Void> response = http.send(
                HttpRequest.newBuilder(URI.create(url("newNonce")))
                        .timeout(Duration.ofSeconds(30))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        return response.headers().firstValue("Replay-Nonce").orElseThrow(() -> new IOException("no nonce"));
    }

    /**
     * Sends a signed request.
     *
     * @param payload JSON, or {@code null} for POST-as-GET
     */
    private HttpResponse<String> post(String url, String payload)
            throws IOException, InterruptedException, GeneralSecurityException {
        for (int attempt = 0; ; attempt++) {
            JsonObject header = new JsonObject();
            header.addProperty("alg", "ES256");
            header.addProperty("nonce", freshNonce());
            header.addProperty("url", url);
            if (accountUrl != null) {
                header.addProperty("kid", accountUrl);
            } else {
                header.add("jwk", JsonParser.parseString(jwk()));
            }
            String protectedPart = B64.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8));
            String payloadPart = payload == null ? "" : B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(account.getPrivate());
            signature.update((protectedPart + "." + payloadPart).getBytes(StandardCharsets.US_ASCII));
            JsonObject body = new JsonObject();
            body.addProperty("protected", protectedPart);
            body.addProperty("payload", payloadPart);
            body.addProperty("signature", B64.encodeToString(signature.sign()));

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/jose+json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            nonce = response.headers().firstValue("Replay-Nonce").orElse(null);
            if (response.statusCode() == 400 && response.body().contains("urn:ietf:params:acme:error:badNonce")
                    && attempt < 3) {
                continue;
            }
            check(response);
            return response;
        }
    }

    private static void check(HttpResponse<String> response) throws IOException {
        if (response.statusCode() >= 400) {
            String detail = response.body();
            try {
                JsonObject problem = JsonParser.parseString(detail).getAsJsonObject();
                if (problem.has("detail")) {
                    detail = problem.get("detail").getAsString();
                }
            } catch (RuntimeException ignored) {
                // not JSON, keep the body
            }
            throw new IOException("ACME error " + response.statusCode() + ": " + detail);
        }
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /** The account's public key as JWK, members in the order the thumbprint needs (RFC 7638). */
    private String jwk() {
        ECPublicKey key = (ECPublicKey) account.getPublic();
        return "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + B64.encodeToString(coordinate(key.getW().getAffineX()))
                + "\",\"y\":\"" + B64.encodeToString(coordinate(key.getW().getAffineY())) + "\"}";
    }

    String thumbprint() throws GeneralSecurityException {
        return B64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(jwk().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] coordinate(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, fixed, 32 - length, length);
        return fixed;
    }

    /** A PKCS#10 certificate signing request for the domain. */
    static byte[] csr(String domain, KeyPair key) throws GeneralSecurityException {
        byte[] alternativeNames = Der.seq(Der.seq(
                Der.oid("2.5.29.17"),
                Der.tlv(0x04, Der.seq(Der.tlv(0x82, domain.getBytes(StandardCharsets.US_ASCII))))));
        byte[] info = Der.seq(
                Der.integer(BigInteger.ZERO),
                Der.commonName(domain),
                key.getPublic().getEncoded(),
                // extensionRequest attribute with the subject alternative name
                Der.tlv(0xA0, Der.seq(Der.oid("1.2.840.113549.1.9.14"), Der.set(alternativeNames))));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key.getPrivate());
        signature.update(info);
        return Der.seq(info, Der.seq(Der.oid("1.2.840.10045.4.3.2")), Der.bits(signature.sign()));
    }
}
