# Changelog

## 1.0.0

The first release.

- Photo code item with its own texture on Java (resource pack served by the plugin) and Bedrock (Geyser mapping),
  crafted from four ink sacs around an empty map.
- Right-clicking the code shows the QR code of an upload page on a map; the link also appears in chat.
- Upload page in the look of a Minecraft menu: camera, selfie or gallery, picture size in maps, moving, zooming,
  rotating, leaving squares out and a preview in real map colours. German and English.
- HTTPS with your own certificate (Let's Encrypt PEM files or PKCS#12, renewals picked up automatically) or a
  self-signed one; TLS 1.2/1.3 only, expiring tokens, rate limit, strict content security policy.
- Pictures are dithered into the map palette on the server and survive restarts.
- Parts of a picture hang themselves into the empty item frames around the first one.
- Survival: every map of a picture costs one empty map.
