<p align="center">
  <img src="src/main/resources/web/icon.png" alt="" width="96">
</p>

<h1 align="center">PhotoMaps</h1>

<p align="center">
  <b>Craft a photo code, scan it with your phone, hang your photo on the wall.</b><br>
  A Minecraft server plugin for Paper and Spigot 1.21.4+, for Java and Bedrock players.
</p>

<p align="center">
  <img src="images/qr_map.png" alt="The photo code map" width="220">
  <img src="images/web_pick.png" alt="Choosing a picture" width="200">
  <img src="images/web_edit.png" alt="Cutting the picture into maps" width="200">
  <img src="images/web_done.png" alt="The picture arrived" width="200">
</p>

## How it works

1. **Craft a photo code**: four ink sacs around an empty map.

   ```
     . I .        I = ink sac
     I M I        M = empty map
     . I .
   ```

2. **Right-click it**: it turns into a map that shows a QR code. Hold it and scan it with your phone (or click the link
   in chat).
3. **On your phone**: take a photo, a selfie or pick one from your gallery. Choose how many maps wide and high it
   should be, drag and zoom it into place, tap squares you want to leave out and look at the preview in real map
   colours. The page looks and feels like a Minecraft menu.
4. **Send it**: the parts arrive in your inventory as maps. Put one into an empty item frame on a wall and the other
   parts hang themselves into the empty frames around it, each in its place.

Right-click the code map again to renew it. In survival, every part of a picture costs one empty map from your
inventory (configurable).

## Java and Bedrock

- The photo code has its own texture. Java players get the resource pack automatically from the plugin's web server
  (it adds files only in its own `photomaps` namespace, next to any other pack). Bedrock players get it through
  Geyser: PhotoMaps copies its Bedrock pack and item mapping into Geyser's folders on startup (or into
  `plugins/PhotoMaps/bedrock` when Geyser runs on a proxy).
- The pictures are vanilla maps, so they look the same on both editions and work in item frames everywhere.
- The code also reacts to right-clicking a block, in case a Bedrock touch screen does not send taps into the air.

## Security

Pictures travel from the phone to the server, so the web server is locked down:

- **HTTPS only** (TLS 1.2 and 1.3). With your own certificate (e.g. Let's Encrypt) phones trust it right away;
  renewed certificate files are picked up automatically. Without one, PhotoMaps creates a self-signed certificate:
  the connection is just as encrypted, but phones show a warning once. If the certificate cannot be loaded the web
  server stays off rather than falling back to plain HTTP.
- Every upload page needs an **unguessable token** (96 random bits) that expires after 15 minutes. Addresses that
  keep guessing tokens are blocked for a minute.
- Uploads are **checked before they are decoded**: exact pixel size, at most 16 MB, one upload per code at a time.
  The server dithers the picture itself and never trusts the browser's preview.
- The page runs under a strict **Content Security Policy**, loads nothing from other servers (the font is bundled),
  sends HSTS, `nosniff` and `no-referrer`.
- Hanging pictures into neighbouring frames asks protection plugins (WorldGuard etc.) about every single frame.

Turning HTTPS off is only meant for running behind a reverse proxy that does HTTPS; the console warns about it.

## Setup

Drop the jar into `plugins/` and start the server. The console prints the address of the web interface, e.g.
`https://192.168.1.20:8100`, which works for phones in the same network.

For players outside your network, open the port in your router/firewall and set `web.public-url`, ideally with a
domain and a real certificate:

```yaml
web:
  port: 8100
  public-url: "https://photos.example.com:8100"
  https:
    certificate: "/etc/letsencrypt/live/photos.example.com/fullchain.pem"
    private-key: "/etc/letsencrypt/live/photos.example.com/privkey.pem"
```

With a self-signed certificate the resource pack is served on `web.https.pack-port` (8101) over plain HTTP, because
Minecraft refuses packs from untrusted certificates; the pack is public and checked by its SHA-1 hash. Open that port
too, or merge the pack into your server resource pack and set `resource-pack.enabled: false`.

All options are explained in [config.yml](src/main/resources/config.yml). Chat texts are in
`messages_en.yml` and `messages_de.yml`; each player sees the language of their client.

## Commands and permissions

| Command | Permission | |
|---|---|---|
| `/photomaps give [player] [amount]` | `photomaps.admin` | give photo codes |
| `/photomaps link` | `photomaps.admin` | open an upload page without a code |
| `/photomaps reload` | `photomaps.admin` | reload config and texts, restart the web server |

| Permission | Default | |
|---|---|---|
| `photomaps.use` | everyone | craft and use photo codes |
| `photomaps.unlimited` | op | uploads cost no empty maps |
| `photomaps.admin` | op | the commands above |

## Development

```
mvn package                               # builds target/PhotoMaps-<version>.jar, runs the tests
python3 scripts/generate_resourcepack.py  # regenerates textures, packs, Geyser mapping and web sprites
```

The pixel art (item texture, web interface sprites and background) is generated from code in
`scripts/generate_resourcepack.py`; commit the generated files. The web interface is plain HTML, CSS and JavaScript
in `src/main/resources/web`.

## Credits

- QR codes: [QR Code generator library](https://www.nayuki.io/page/qr-code-generator-library) by Project Nayuki (MIT)
- Font of the web interface: [Monocraft](https://github.com/IdreesInc/Monocraft) by Idrees Hassan (SIL Open Font
  License 1.1, see `src/main/resources/web/monocraft-LICENSE.txt`)

PhotoMaps is not affiliated with Mojang or Microsoft. Licensed under the [GPL-3.0](LICENSE).
