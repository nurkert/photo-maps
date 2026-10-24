# The web interface

The photo code opens a page on the player's phone that looks and feels like a Minecraft menu. It is served by the
plugin itself, needs no app and no account, and works on any phone browser.

![Choosing a picture](../images/web_pick.png)

## Choosing a picture

- **Take a photo**: opens the back camera.
- **Selfie**: opens the front camera.
- **From gallery**: any picture saved on the phone.

On a computer, a picture can also be dropped onto the page or pasted.

The camera is opened through the browser's file picker, so it works without extra permissions and without the page
ever seeing a live camera image.

## Cutting it into maps

![Cutting the picture into maps](../images/web_edit.png)

- **Width and height** set how many maps the picture spans, up to `upload.max-columns` x `upload.max-rows`. The page
  suggests a size that fits the picture's shape and the empty maps you have.
- **Drag** to move the picture, **pinch** or use the zoom slider to zoom, **Rotate** turns it by 90 degrees.
- **Whole picture** fits the whole picture inside (black bars) instead of filling every map.
- **Tap a square** to leave that map out, for example sky you do not need. Tap again to take it back.
- **Map colours** shows the picture exactly as it will look on the maps: maps have a limited palette, so colours are
  mixed by dithering.

The tooltip below the picture shows how many maps the picture needs and how many empty maps you have.

## Sending

**Send to Minecraft** uploads the picture. The phone only sends what ends up on the maps (128 x 128 pixels per map),
so even big photos upload in a moment. The server converts the picture into map colours and puts the maps into the
player's inventory, or at their feet if the inventory is full.

![The picture arrived](../images/web_done.png)

## Languages

The page is in German or English, following the phone's language. Chat texts follow the language of each player's
Minecraft client, see [Configuration](configuration.md#texts).
