# Bedrock and Geyser

PhotoMaps works for Bedrock players connected through [Geyser](https://geysermc.org) and
[Floodgate](https://geysermc.org/wiki/floodgate/).

## Setup

Install Geyser-Spigot and Floodgate on the same server. On startup PhotoMaps copies `photomaps.mcpack` into
`plugins/Geyser-Spigot/packs/` and `photomaps_mappings.json` into `plugins/Geyser-Spigot/custom_mappings/`. It loads
before Geyser, so this works on the very first start.

Geyser's `enable-custom-content` and `force-resource-packs` must be `true` (the defaults); PhotoMaps warns if either
is off. Turn the automatic installation off with `bedrock.install-pack: false`.

### Geyser on a proxy

If Geyser runs on Velocity or BungeeCord, PhotoMaps puts both files into `plugins/PhotoMaps/bedrock/`. Copy them into
Geyser's `packs` and `custom_mappings` folders on the proxy and restart it. Both are also attached to every release.

## What Bedrock players get

- The photo code with its own texture, in the creative inventory under items.
- The QR code on the map in their hand, and the link in chat.
- Pictures are vanilla maps, so they look the same as on Java, in hand and in item frames.
- The code also reacts to tapping a block, in case a touch screen does not send taps into the air.

Players on a phone cannot scan their own screen: they open the link from chat, or let a friend scan the map.
