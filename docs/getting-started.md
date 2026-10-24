# Getting started

PhotoMaps lets players put their own photos into the world: craft a photo code, scan it with the phone, upload a
picture and get it back as maps that hang on the wall.

## Installing

1. Put `PhotoMaps-<version>.jar` into `plugins/` of a Paper or Spigot server, Minecraft 1.21.4 or newer.
2. Start the server. The console prints the address of the web interface:

   ```
   [PhotoMaps] Web interface running at https://192.168.1.20:8100
   ```

3. Phones in the same network can use it right away (they show a certificate warning once).
4. For a public server with a domain, enter the domain and PhotoMaps gets a trusted certificate from Let's Encrypt by
   itself:

   ```yaml
   web:
     https:
       lets-encrypt:
         domain: "photos.example.com"
   ```

   Open port 8100 for the page and port 80 for Let's Encrypt's check, then `/photomaps reload`. Details in
   [Security and HTTPS](security.md).

Bedrock players through Geyser need nothing extra, see [Bedrock and Geyser](bedrock.md).

## Crafting a photo code

Four ink sacs around an empty map:

| | | |
|---|---|---|
| | Ink sac | |
| Ink sac | Empty map | Ink sac |
| | Ink sac | |

Admins can hand them out with `/photomaps give`.

## Uploading a picture

1. **Right-click the photo code.** It turns into a map that shows a QR code, and the link appears in chat (Java
   players can click it).
2. **Scan the code** with your phone camera while holding the map.
3. **Choose a picture**: take a photo, a selfie, or pick one from your gallery. See [The web interface](web-interface.md).
4. **Send it.** The parts arrive in your inventory as maps.

The code stays valid for 15 minutes (configurable); right-click it again to renew it. The same code can be used for
many pictures.

In survival, every part of a picture costs one empty map from your inventory, so get some maps before you scan.
Creative players and players with `photomaps.unlimited` pay nothing.

## Hanging a picture

Put any part of the picture into an empty item frame on a wall. The other parts in your inventory hang themselves
into the empty frames around it, each at its place in the picture. Place the frames first: one per map, in a
rectangle the size of the picture.

Pictures are vanilla maps: they can be copied in a cartography table or crafting grid like any map, and they keep
their picture after server restarts.
