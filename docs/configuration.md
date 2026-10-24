# Configuration

Everything is in `plugins/PhotoMaps/config.yml`. Apply changes with `/photomaps reload`; the web server restarts.

## Web server

| Option | Default | |
|---|---|---|
| `web.port` | `8100` | port of the web interface |
| `web.bind` | `0.0.0.0` | address to listen on, `0.0.0.0` means all |
| `web.public-url` | empty | address the QR codes point to, e.g. `https://photos.example.com:8100`. Empty: `https://<server address>:<port>`, the local network address if `server-ip` is not set |
| `web.https.enabled` | `true` | HTTPS; only turn it off behind a reverse proxy, see [Security and HTTPS](security.md) |
| `web.https.certificate` | empty | PEM certificate chain, e.g. Let's Encrypt's `fullchain.pem` |
| `web.https.private-key` | empty | PEM private key, e.g. `privkey.pem` |
| `web.https.keystore` | empty | PKCS#12 key store instead of PEM files |
| `web.https.keystore-password` | empty | its password |
| `web.https.pack-port` | `8101` | plain HTTP port for the resource pack, only with a self-signed certificate; `0` turns it off |

## Uploads

| Option | Default | |
|---|---|---|
| `upload.session-minutes` | `15` | how long a scanned code stays valid |
| `upload.max-columns` | `5` | widest picture, in maps (at most 10) |
| `upload.max-rows` | `5` | highest picture, in maps (at most 10) |
| `upload.consume-empty-maps` | `true` | every map of a picture costs one empty map in survival |

## Items and the world

| Option | Default | |
|---|---|---|
| `frames.auto-place` | `true` | parts of a picture hang themselves into the empty frames around the clicked one |
| `recipe.enabled` | `true` | the crafting recipe of the photo code |

## Resource pack

| Option | Default | |
|---|---|---|
| `resource-pack.enabled` | `true` | send the pack with the photo code's texture to Java players |
| `resource-pack.url` | empty | host the pack yourself instead of the plugin's web server |
| `resource-pack.required` | `false` | kick players who decline it |
| `resource-pack.prompt` | empty | text shown when the pack is offered |
| `bedrock.install-pack` | `true` | copy the Bedrock pack and item mapping into Geyser on startup |

The pack only adds files in the `photomaps` namespace, so it can be merged into a server resource pack: take
`PhotoMaps-resourcepack-<version>.zip` from the release and set `resource-pack.enabled: false`.

## Texts

Chat and item texts are in `messages_en.yml` and `messages_de.yml`. Every player sees the language of their
Minecraft client (English for every language other than German). Colour codes use `&`. Keys missing in the files fall
back to the built-in texts, so updates never show empty messages.

## Files

| File | |
|---|---|
| `pictures/<map id>.bin` | the pixels of every picture map (16 KB each, gzipped). Deleting one leaves that map blank |
| `codes.yml` | the ids of the photo code maps |
| `tls/self-signed.p12` | the self-signed certificate, recreated when the server's address changes |
