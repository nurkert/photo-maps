# Configuration

Everything is in `plugins/PhotoMaps/config.yml`. Apply changes with `/photomaps reload`; the web server restarts.

## Web server

| Option | Default | |
|---|---|---|
| `web.port` | `8100` | port of the web interface |
| `web.bind` | `0.0.0.0` | address to listen on, `0.0.0.0` means all |
| `web.public-url` | empty | address the QR codes point to, e.g. `https://photos.example.com:8100`. Empty: `https://<lets-encrypt domain>:<port>`, else `https://<server address>:<port>` (the local network address if `server-ip` is not set) |
| `web.https.enabled` | `true` | HTTPS; only turn it off behind a reverse proxy, see [Security and HTTPS](security.md) |
| `web.https.lets-encrypt.domain` | empty | domain pointing to this server: PhotoMaps gets and renews a Let's Encrypt certificate for it, see [Security and HTTPS](security.md#lets-encrypt-built-in-recommended) |
| `web.https.lets-encrypt.email` | empty | optional contact address for Let's Encrypt |
| `web.https.lets-encrypt.challenge-port` | `80` | port that Let's Encrypt's check on port 80 arrives at |
| `web.https.lets-encrypt.staging` | `false` | untrusted test certificates, for trying out the setup |
| `web.https.certificate` | empty | PEM certificate chain, e.g. certbot's `fullchain.pem` (wins over `lets-encrypt`) |
| `web.https.private-key` | empty | PEM private key, e.g. `privkey.pem` |
| `web.https.keystore` | empty | PKCS#12 key store instead of PEM files |
| `web.https.keystore-password` | empty | its password |
| `web.https.pack-port` | `8101` | development builds with a self-signed certificate only: plain HTTP port for the resource pack; `0` turns it off |

## Uploads

| Option | Default | |
|---|---|---|
| `upload.session-minutes` | `15` | how long a scanned code stays valid |
| `upload.max-columns` | `5` | widest picture, in maps (at most 10) |
| `upload.max-rows` | `5` | highest picture, in maps (at most 10) |
| `upload.consume-empty-maps` | `true` | every map of a picture costs one empty map in survival |
| `upload.single-use` | `true` | one picture per photo code; afterwards the code is used up |

## Items and the world

| Option | Default | |
|---|---|---|
| `frames.auto-place` | `true` | parts of a picture hang themselves into the empty frames around the clicked one |
| `recipe.enabled` | `true` | the crafting recipe of the photo code |

## Resource pack

| Option | Default | |
|---|---|---|
| `resource-pack.enabled` | `true` | send the pack with the photo code's texture to Java players |
| `resource-pack.url` | empty | host the pack yourself; by default it comes from the GitHub release of the installed version |
| `resource-pack.required` | `false` | kick players who decline it |
| `resource-pack.prompt` | empty | text shown when the pack is offered |
| `bedrock.install-pack` | `true` | copy the Bedrock pack and item mapping into Geyser on startup |

The pack is sent in addition to other packs and only adds files in the `photomaps` namespace, so it works next to
PorticleGun and server resource packs. If a player cannot load it, the console says so with the reason. It can also be
merged into a server resource pack: take
`PhotoMaps-resourcepack-<version>.zip` from the release and set `resource-pack.enabled: false`.

## Texts

Chat and item texts are in `messages_en.yml` and `messages_de.yml`. Every player sees the language of their
Minecraft client (English for every language other than German). Colour codes use `&`. Keys missing in the files fall
back to the built-in texts, so updates never show empty messages.

## Files

| File | |
|---|---|
| `pictures/<map id>.bin` | the pixels of every picture map (16 KB each, gzipped), written before the map is handed out. Back them up with the world; deleting one leaves that map blank |
| `codes.yml` | the photo code maps, who they belong to and which are used up |
| `tls/self-signed.p12` | the self-signed certificate, recreated when the server's address changes |
| `tls/lets-encrypt/` | the Let's Encrypt account key and, per domain, `fullchain.pem` and `privkey.pem` |
