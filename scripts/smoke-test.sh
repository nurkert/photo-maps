#!/usr/bin/env bash
# Starts a real Paper server with the plugin, checks the web interface over HTTPS, reloads, stops the server again
# and fails if PhotoMaps did not enable cleanly or logged errors.
#
# usage: scripts/smoke-test.sh <plugin.jar> [minecraft-version] [geyser]
#   geyser: also install Geyser and Floodgate and check that the Bedrock pack is installed
set -euo pipefail

PLUGIN_JAR="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
MC_VERSION="${2:-1.21.4}"
WITH_GEYSER="${3:-}"
STARTUP_TIMEOUT=300
WEB_PORT=8188
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/run/smoke-$MC_VERSION${WITH_GEYSER:+-$WITH_GEYSER}"
CACHE="$ROOT/run/cache"

mkdir -p "$CACHE"
rm -rf "$WORK"
mkdir -p "$WORK/plugins/PhotoMaps"

# download Paper (cached per version and checksum; falls back to the cache when the API is unreachable)
PAPER_JAR=""
if BUILD_JSON="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$MC_VERSION/builds/latest")"; then
    read -r PAPER_URL PAPER_SHA <<<"$(python3 -c '
import json, sys
d = json.loads(sys.argv[1])["downloads"]["server:default"]
print(d["url"], d["checksums"]["sha256"])' "$BUILD_JSON")"
    PAPER_JAR="$CACHE/paper-$MC_VERSION-$PAPER_SHA.jar"
    if [ ! -f "$PAPER_JAR" ]; then
        echo "Downloading $PAPER_URL"
        curl -fsSL -o "$PAPER_JAR.part" "$PAPER_URL"
        echo "$PAPER_SHA  $PAPER_JAR.part" | shasum -a 256 -c - >/dev/null
        mv "$PAPER_JAR.part" "$PAPER_JAR"
    fi
else
    PAPER_JAR="$(ls -t "$CACHE"/paper-"$MC_VERSION"-*.jar 2>/dev/null | head -n 1 || true)"
    [ -n "$PAPER_JAR" ] || { echo "Paper API unreachable and no cached Paper $MC_VERSION jar"; exit 1; }
    echo "Paper API unreachable, using cached $PAPER_JAR"
fi

cp "$PLUGIN_JAR" "$WORK/plugins/"
if [ "$WITH_GEYSER" = "geyser" ]; then
    for project in geyser floodgate; do
        curl -fsSL -o "$WORK/plugins/$project.jar" \
            "https://download.geysermc.org/v2/projects/$project/versions/latest/builds/latest/downloads/spigot"
    done
fi
echo "eula=true" >"$WORK/eula.txt"
cat >"$WORK/server.properties" <<PROPS
online-mode=false
server-port=25599
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
PROPS
cat >"$WORK/plugins/PhotoMaps/config.yml" <<YAML
web:
  port: $WEB_PORT
  https:
    pack-port: $((WEB_PORT + 1))
YAML

LOG="$WORK/server.log"
RESULTS="$WORK/web-checks.txt"
: >"$LOG"
: >"$RESULTS"
cd "$WORK"

# once the server is up: check the web interface, reload, check again, stop
(
    for _ in $(seq "$STARTUP_TIMEOUT"); do
        grep -q "Done (" "$LOG" 2>/dev/null && break
        sleep 1
    done
    sleep 3
    {
        echo "https-page $(curl -sk -o /dev/null -w '%{http_code}' "https://localhost:$WEB_PORT/s/AAAAAAAAAAAAAAAA")"
        echo "https-unknown-session $(curl -sk -o /dev/null -w '%{http_code}' "https://localhost:$WEB_PORT/api/session/AAAAAAAAAAAAAAAA")"
        echo "plain-http $(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$WEB_PORT/" || true)"
        echo "tls11 $(curl -sk --tls-max 1.1 -o /dev/null -w '%{http_code}' "https://localhost:$WEB_PORT/" || true)"
        echo "hsts $(curl -skI "https://localhost:$WEB_PORT/" | grep -ci strict-transport-security || true)"
    } >>"$RESULTS"
    echo "photomaps reload"
    sleep 3
    echo "reload-page $(curl -sk -o /dev/null -w '%{http_code}' "https://localhost:$WEB_PORT/")" >>"$RESULTS"
    echo "photomaps give NobodyOnline"
    sleep 1
    echo "stop"
) | java -Xms1G -Xmx2G -jar "$PAPER_JAR" --nogui >"$LOG" 2>&1 || true

fail() {
    echo "SMOKE TEST FAILED: $1"
    echo "----- web checks -----"
    cat "$RESULTS"
    echo "----- server.log (PhotoMaps / errors) -----"
    grep -n -i -E "photomaps|exception|error" "$LOG" | head -100 || true
    exit 1
}

check() {
    grep -q "^$1 $2$" "$RESULTS" || fail "$3"
}

grep -q "Done (" "$LOG" || fail "server did not finish starting within ${STARTUP_TIMEOUT}s"
grep -q "\[PhotoMaps\] Web interface running at https://" "$LOG" || fail "the web interface did not start with HTTPS"
grep -q "\[PhotoMaps\] Using a self-signed certificate" "$LOG" || fail "no self-signed certificate was created"
[ -f "$WORK/plugins/PhotoMaps/tls/self-signed.p12" ] || fail "the self-signed certificate was not stored"
grep -q "Disabling PhotoMaps" "$LOG" || fail "PhotoMaps was not disabled on shutdown"
check https-page 200 "the upload page is not served over HTTPS"
check https-unknown-session 404 "an unknown session was not rejected"
check plain-http 000 "the web server answered plain HTTP"
check tls11 000 "the web server accepted TLS 1.1"
check hsts 1 "no HSTS header"
check reload-page 200 "the web server did not come back after /photomaps reload"
grep -q "Configuration reloaded" "$LOG" || fail "/photomaps reload did not answer"
grep -q "Player NobodyOnline is not online" "$LOG" || fail "/photomaps give did not answer"
if [ "$WITH_GEYSER" = "geyser" ]; then
    grep -q "Installed the PhotoMaps Bedrock pack" "$LOG" || fail "the Bedrock pack was not installed into Geyser"
    [ -f "$WORK/plugins/Geyser-Spigot/packs/photomaps.mcpack" ] || fail "photomaps.mcpack missing in Geyser's packs folder"
    [ -f "$WORK/plugins/Geyser-Spigot/custom_mappings/photomaps_mappings.json" ] || fail "item mapping missing in Geyser"
fi
if grep -n -E "Error occurred while (enabling|disabling) PhotoMaps|Could not load 'plugins/PhotoMaps" "$LOG"; then
    fail "PhotoMaps failed to load, enable or disable"
fi
if grep -n -B2 -A8 -E "^\s+at eu\.nurkert\.photomaps\." "$LOG"; then
    fail "an exception was thrown from PhotoMaps code"
fi

echo "Smoke test passed on Paper $MC_VERSION${WITH_GEYSER:+ with Geyser and Floodgate}"
