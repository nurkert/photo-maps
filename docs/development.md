# Development

## Building

```
mvn package
```

builds `target/PhotoMaps-<version>.jar` (with the QR code library shaded in), the Java resource pack and the Bedrock
pack, and runs the unit tests. Java 21 and Maven are needed.

## Pixel art and packs

The photo code's texture, the Java and Bedrock packs, the Geyser mapping and the sprites and background of the web
interface are generated from code:

```
python3 scripts/generate_resourcepack.py
```

Commit the generated files; the build only zips them. CI checks that the generated JSON files are up to date.

## The web interface

Plain HTML, CSS and JavaScript in `src/main/resources/web`, no build step. The page asks the server for the map
palette and dithers its preview with the same algorithm as `Palette.java`, so the preview matches the maps.

## Smoke test

```
scripts/smoke-test.sh target/PhotoMaps-<version>.jar 1.21.4 [geyser]
```

starts a real Paper server with the plugin (and Geyser and Floodgate with `geyser`), checks the web interface over
HTTPS (plain HTTP and TLS 1.1 must be refused), runs `/photomaps reload` and fails on any exception from PhotoMaps.
CI runs it on every push.

## Let's Encrypt

`LetsEncryptTest` gets a real certificate from [Pebble](https://github.com/letsencrypt/pebble), Let's Encrypt's test
CA, when `PEBBLE_DIRECTORY`, `PEBBLE_CA` and `PEBBLE_CHALLENGE_PORT` are set; CI starts Pebble in Docker for it (see
the `lets-encrypt` job in `.github/workflows/ci.yml`). Without them the test is skipped.

## Website and wiki

```
pip install markdown==3.7
python3 scripts/build_site.py _site        # then open _site/index.html
```

builds the landing page from `site/` and the wiki from `docs/` and the changelog. GitHub Pages deploys it on every
change to `main`.

## Releasing

Add the release to `CHANGELOG.md`, then push a tag:

```
git tag v1.0.0 && git push origin v1.0.0
```

The release workflow builds that version, runs the smoke test and publishes the jar, both packs and the Geyser
mapping on GitHub.
