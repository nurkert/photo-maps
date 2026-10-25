# Changelog

## 1.0.2 (2026-10-25)

- Fixed: the web server stopped answering after a while on public servers. Port scanners open connections and never
  send a request; four of them blocked all of its threads for good. Every connection now gets its own thread and
  unfinished requests are closed after 30 seconds.
- One picture per photo code: after the upload the code is used up, leaves the inventory and its link stops working.
  `upload.single-use: false` brings back unlimited uploads.
- Every right-click on a photo code makes a new QR code; the old link stops working at once.
- Only PNG uploads are accepted (content type, signature, PNG decoder only), no bigger than a picture of the chosen
  size can be. The upload is never stored, only the map colours computed from it.
- Pictures in item frames show no label any more. Pictures hung with earlier versions lose theirs when their area
  loads; names given on an anvil stay.

## 1.0.1 (2026-10-24)

- The resource pack now comes from the GitHub release, like PorticleGun's. Before, it came from the plugin's own web
  server, which players often could not reach (internal address, closed port 8101); because Minecraft applies a
  server's packs together, that also held back the textures of other plugins such as PorticleGun.
- The console names players whose game could not load the pack, with the reason.
- Port 8101 is only opened by development builds.

## 1.0.0 (2026-10-24)

The first release.

- Photo code item with its own texture on Java (resource pack served by the plugin) and Bedrock (Geyser mapping),
  crafted from four ink sacs around an empty map.
- Right-clicking the code shows the QR code of an upload page on a map; the link also appears in chat.
- Upload page in the look of a Minecraft menu: camera, selfie or gallery, picture size in maps, moving, zooming,
  rotating, leaving squares out and a preview in real map colours. German and English.
- HTTPS with a Let's Encrypt certificate that PhotoMaps gets and renews by itself (just enter the domain), your own
  certificate files (PEM or PKCS#12, renewals picked up automatically) or a self-signed one; TLS 1.2/1.3 only,
  expiring tokens, rate limit, strict content security policy.
- Pictures are dithered into the map palette on the server and written to disk before the maps are handed out, so
  they look the same after rejoining and restarting.
- Photo codes belong to the player who activated them: only they see the QR code, copies cannot be taken over.
- Parts of a picture hang themselves into the empty item frames around the first one.
- Survival: every map of a picture costs one empty map.
