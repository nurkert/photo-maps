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

![Photo code recipe: four ink sacs around an empty map](../images/crafting_photo_code.png)

Admins can hand them out with `/photomaps give`.

## Uploading a picture

1. **Right-click the photo code.** It turns into a map that shows a QR code, and the link appears in chat (Java
   players can click it).
2. **Scan the code** with your phone camera while holding the map.
3. **Choose a picture**: take a photo, a selfie, or pick one from your gallery. See [The web interface](web-interface.md).
4. **Send it.** The parts arrive in your inventory as maps.

**One picture per code.** After the upload the code is used up: it leaves your inventory, and its link stops working.
Craft a new code for the next picture (admins can turn this off with `upload.single-use: false`).

Before that, the code stays valid for 15 minutes (configurable). Right-clicking it gives it a new QR code; the old one
stops working at once.

A code belongs to the player who activated it. Copies of the map (in item frames, from a cartography table) show
everyone else "Not your photo code" instead of the QR code, because the QR code is the key to your upload page, and
right-clicking a copy does not take it over.

In survival, every part of a picture costs one empty map from your inventory, so get some maps before you scan.
Creative players and players with `photomaps.unlimited` pay nothing.

## Hanging a picture

Put any part of the picture into an empty item frame on a wall. The other parts in your inventory hang themselves
into the empty frames around it, each at its place in the picture. Place the frames first: one per map, in a
rectangle the size of the picture.

Pictures are vanilla maps: they can be copied in a cartography table or crafting grid like any map. Every picture
is written to disk before its maps are handed out, so it looks exactly the same after rejoining and after a server
restart, even one right after the upload.
