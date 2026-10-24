# Security and HTTPS

Players upload their photos from their phones to your server, so PhotoMaps treats that path with care.

## Encryption

The web server speaks **HTTPS only**, with TLS 1.2 and 1.3. Plain HTTP and older TLS versions are refused. Nobody in
the Wi-Fi or on the way through the internet can see the pictures.

There are three ways to get a certificate:

### Your own certificate (recommended for public servers)

With a domain pointing at your server, get a free certificate from [Let's Encrypt](https://letsencrypt.org) (for
example with certbot) and point PhotoMaps at it:

```yaml
web:
  public-url: "https://photos.example.com:8100"
  https:
    certificate: "/etc/letsencrypt/live/photos.example.com/fullchain.pem"
    private-key: "/etc/letsencrypt/live/photos.example.com/privkey.pem"
```

The Minecraft server needs read access to both files. Phones trust the certificate without any warning. When
certbot renews the files, PhotoMaps notices within an hour and restarts its web server with the new certificate.

Keys in PKCS#8 (`BEGIN PRIVATE KEY`, what certbot writes) and RSA keys in PKCS#1 (`BEGIN RSA PRIVATE KEY`) are read
directly. A PKCS#12 key store works as well:

```yaml
web:
  https:
    keystore: "plugins/PhotoMaps/photos.p12"
    keystore-password: "secret"
```

### A self-signed certificate (default)

Without a certificate, PhotoMaps creates one for the server's address and keeps it in
`plugins/PhotoMaps/tls/self-signed.p12`. The connection is just as encrypted, but phones cannot verify who signed it
and show a warning the first time ("This connection is not private"); players continue with "Show details" /
"Advanced". Players are told so in chat. The console prints the certificate's SHA-256 fingerprint, which can be
compared with what the browser shows.

This is fine for LAN servers and friends. For public servers, use your own certificate.

Minecraft itself refuses to download resource packs from self-signed servers, so in this mode the resource pack is
offered over plain HTTP on `web.https.pack-port` (8101). The pack contains only the photo code's texture and
Minecraft checks it against its SHA-1 hash. Set `pack-port: 0` to turn this off.

### Behind a reverse proxy

If nginx, Caddy or similar already terminate HTTPS for your domain, let them forward to PhotoMaps over plain HTTP:

```yaml
web:
  bind: "127.0.0.1"
  public-url: "https://photos.example.com"
  https:
    enabled: false
```

The console warns whenever HTTPS is off and the public URL is not `https://`.

If the configured certificate cannot be loaded, the web server stays off instead of falling back to plain HTTP.

## Access

- Every upload page is reachable only through its **token**: 96 random bits, impossible to guess, and valid for
  `upload.session-minutes` (15). A page belongs to the player who right-clicked the code.
- An address that asks for more than 20 unknown tokens within a minute is blocked for the rest of that minute.
- Only players with `photomaps.use` can open upload pages.

## Uploads

- The page sends a PNG of exactly the chosen size (128 x 128 pixels per map). The server reads the size from the file
  header and rejects anything else **before decoding** it; uploads are limited to 16 MB.
- One upload per code at a time.
- The server converts the picture into map colours itself; nothing the browser computes is trusted.
- Maps are only created on the main server thread, after the player's empty maps were checked.

## The page

- A strict Content Security Policy: scripts, styles, fonts and images only from the plugin itself, no frames, no
  forms to other sites.
- No external requests at all: the font and every image are inside the plugin. Phones talk only to your server.
- HSTS, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and the pages are marked `noindex`.

## In the world

Hanging the other parts of a picture into neighbouring item frames asks protection plugins such as WorldGuard about
every single frame, exactly as if the player had clicked it.
