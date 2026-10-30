#!/usr/bin/env bash
# Development commands. Everything runs in Docker (inside WSL on Windows), so no Java or Android SDK on the machine.
#
#   scripts/dev.sh image              build the build image (Java + Android SDK)
#   scripts/dev.sh test               unit tests
#   scripts/dev.sh apk                debug APK -> dist/
#   scripts/dev.sh apk-shrunk         the release's shrinking with the test key and log -> dist/
#   scripts/dev.sh screenshots        pictures of the screens (example data) -> docs/screenshots/
#   scripts/dev.sh ts-server up|down  start/stop the TeamSpeak test server container (TS_CONTAINER)
#   scripts/dev.sh it                 tests against that server (TS_TEST_SERVER)
#   scripts/dev.sh devserver up|down  the page phones install test builds from and send their log to (DEV_PORT)
#   scripts/dev.sh viewer up|down     a "Stream viewer" on the test server that watches every stream, for testing
#                                     sharing without a second device; the dev page shows what it sees (/viewer).
#                                     It takes H.264 as the TeamSpeak app does; "viewer up webts" lets WebTS choose
#   scripts/dev.sh gradle <args>      any Gradle command
#   scripts/dev.sh keystore           make the release key (once; keep it safe, see DEVELOPING.md)
#   scripts/dev.sh release            the signed release APK -> dist/ (asks for the key's password)
#   scripts/dev.sh play-key PEPK PEM  the release key, encrypted for Google Play App Signing (asks for the password)
#
# This machine's own settings go in dev.env next to this repo's README (never committed), for example:
#   TS_CONTAINER=my-teamspeak-test      an existing TeamSpeak test server container
#   TS_TEST_SERVER=127.0.0.1:9987       where the tests connect to it
#   TS_TEST_FT_PORT=30033               its file transfer port, when published under another number
#   DEV_PORT=8686
#   POCKETTS_KEYSTORE=~/.pocketts/release.keystore   where the release key is (outside the repo; this is the default)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$ROOT/dev.env" ] && . "$ROOT/dev.env"

IMAGE=pocketts-build
KEYSTORE="${POCKETTS_KEYSTORE:-$HOME/.pocketts/release.keystore}"
KEYSTORE="${KEYSTORE/#\~/$HOME}"
EXTRA=()  # more docker run options for one command
DEV_NAME=pocketts-dev
DEV_PORT="${DEV_PORT:-8686}"
VIEWER_NAME=pocketts-viewer
WEBTS_IMAGE="${WEBTS_IMAGE:-ghcr.io/korppujauho/webts:latest}"  # the stream viewer watches through WebTS
CPUS="${CPUS:-4}"   # keep builds from taking every core

gradle() {
  # The build output goes to a Docker volume (fast), not next to the sources on the Windows drive.
  # Host networking, so the tests reach a test server published on this machine.
  docker run --rm -t --network host --cpus "$CPUS" --memory 6g "${EXTRA[@]}" \
    -v "$ROOT":/work -v pocketts-gradle:/gradle -v pocketts-out:/out \
    -e ANDROID_USER_HOME=/gradle/android-user \
    -e POCKETTS_BUILD_ROOT=/out -e TS_TEST_SERVER="${TS_TEST_SERVER_FOR_TESTS:-}" -e TS_TEST_FT_PORT="${TS_TEST_FT_PORT:-}" \
    "$IMAGE" ./gradlew --no-daemon --console=plain "$@"
}

need() { [ -n "${!1:-}" ] || { echo "Set $1 in dev.env"; exit 1; }; }

case "${1:-}" in
  image)
    docker build -f "$ROOT/docker/build.Dockerfile" -t "$IMAGE" \
      --build-arg ACCEPT_ANDROID_SDK_LICENSE="${ACCEPT_ANDROID_SDK_LICENSE:-no}" "$ROOT/docker"
    ;;
  test)
    gradle :tsproto:test
    ;;
  screenshots)
    gradle :app:recordRoborazziDebug
    ;;
  apk)
    gradle :app:assembleDebug
    mkdir -p "$ROOT/dist"
    docker run --rm -v pocketts-out:/out -v "$ROOT/dist":/dist "$IMAGE" \
      bash -c 'cp /out/app/outputs/apk/debug/*.apk /dist/ && ls -l /dist'
    ;;
  apk-shrunk)
    # The release's shrinking, with the test key and the test log: for finding what shrinking broke
    gradle :app:assembleShrunk
    mkdir -p "$ROOT/dist"
    docker run --rm -v pocketts-out:/out -v "$ROOT/dist":/dist "$IMAGE" \
      bash -c 'cp /out/app/outputs/apk/shrunk/app-shrunk.apk /dist/PocketTS-shrunk-$(date +%Y%m%d-%H%M).apk && ls /dist | tail -1'
    ;;
  ts-server)
    need TS_CONTAINER
    case "${2:-}" in
      up) docker start "$TS_CONTAINER" >/dev/null && echo "$TS_CONTAINER started" ;;
      down) docker stop "$TS_CONTAINER" >/dev/null && echo "$TS_CONTAINER stopped" ;;
      *) echo "ts-server up|down"; exit 1 ;;
    esac
    ;;
  it)
    need TS_TEST_SERVER
    TS_TEST_SERVER_FOR_TESTS="$TS_TEST_SERVER" gradle :tsproto:test --tests '*ServerTest*' --rerun
    ;;
  devserver)
    case "${2:-}" in
      up)
        mkdir -p "$ROOT/dist"
        docker rm -f "$DEV_NAME" >/dev/null 2>&1 || true
        docker run -d --name "$DEV_NAME" --cpus 1 -p "$DEV_PORT":8686 \
          -v "$ROOT/tools/devserver":/app:ro -v "$ROOT/dist":/dist \
          node:22-slim node /app/server.mjs >/dev/null
        echo "Test builds and phone logs: http://<this machine's address>:$DEV_PORT"
        ;;
      down) docker rm -f "$DEV_NAME" >/dev/null 2>&1 || true ;;
      *) echo "devserver up|down"; exit 1 ;;
    esac
    ;;
  viewer)
    # WebTS and a headless Chromium inside the test server's own network: they reach it as 127.0.0.1, and nothing
    # is opened to the outside. The viewer writes its log and pictures to dist/viewer.
    need TS_CONTAINER
    case "${2:-}" in
      up)
        docker build -q -t "$VIEWER_NAME" "$ROOT/tools/stream-viewer" >/dev/null
        docker rm -f "$VIEWER_NAME" "$VIEWER_NAME-webts" >/dev/null 2>&1 || true
        docker run -d --name "$VIEWER_NAME-webts" --cpus 1 --memory 512m --network "container:$TS_CONTAINER" \
          -e TS_HOST=127.0.0.1 -e TS_PORT=9987 -e PORT=3002 -e ACCESS_MODE=public -e I_UNDERSTAND_THIS_IS_PUBLIC=true \
          -e NICK_PREFIX= -e MAX_USERS=2 -e PICTURES=false -v "$VIEWER_NAME-webts:/app/data" "$WEBTS_IMAGE" >/dev/null
        mkdir -p "$ROOT/dist/viewer"
        rm -f "$ROOT/dist/viewer/viewer.log"
        # Video as H.264 like the TeamSpeak app takes it; "viewer up webts" lets WebTS choose (VP8 from PocketTS)
        docker run -d --name "$VIEWER_NAME" --cpus 2 --memory 2g --shm-size 512m --network "container:$TS_CONTAINER" \
          -e VIEWER_CODEC="${3:-h264}" -v "$ROOT/dist/viewer:/out" "$VIEWER_NAME" >/dev/null
        echo "Stream viewer started: the dev page shows what it sees (/viewer)"
        ;;
      down) docker rm -f "$VIEWER_NAME" "$VIEWER_NAME-webts" >/dev/null 2>&1 || true ;;
      *) echo "viewer up|down"; exit 1 ;;
    esac
    ;;
  gradle)
    shift
    gradle "$@"
    ;;
  keystore)
    # The release key: every update must be signed with it, so losing it means users can't update. Back it up.
    if [ -e "$KEYSTORE" ]; then echo "$KEYSTORE already exists: not made again."; exit 1; fi
    mkdir -p "$(dirname "$KEYSTORE")"
    chmod 700 "$(dirname "$KEYSTORE")"
    # As this user, so the key file is theirs (Docker would make it root's)
    docker run --rm -it --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$(dirname "$KEYSTORE")":/keys "$IMAGE" \
      keytool -genkeypair -keystore "/keys/$(basename "$KEYSTORE")" -storetype PKCS12 -alias pocketts \
      -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=PocketTS"
    chmod 600 "$KEYSTORE"
    echo "Made $KEYSTORE. Back it up somewhere safe (with its password): updates need it."
    ;;
  release)
    [ -f "$KEYSTORE" ] || { echo "No release key at $KEYSTORE: make one with scripts/dev.sh keystore"; exit 1; }
    read -r -s -p "Release key password: " KEYPASS; echo
    # The password goes to the build only, through the environment (not on the command line, not into files)
    export POCKETTS_KEYSTORE_PASSWORD="$KEYPASS"
    EXTRA=(-v "$(dirname "$KEYSTORE")":/keys:ro -e POCKETTS_KEYSTORE_FILE="/keys/$(basename "$KEYSTORE")" -e POCKETTS_KEYSTORE_PASSWORD)
    gradle :app:assembleRelease
    unset POCKETTS_KEYSTORE_PASSWORD KEYPASS
    VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -1)
    mkdir -p "$ROOT/dist"
    docker run --rm -v pocketts-out:/out -v "$ROOT/dist":/dist "$IMAGE" bash -c \
      "cp /out/app/outputs/apk/release/app-release.apk /dist/PocketTS-$VERSION.apk && cd /dist && sha256sum PocketTS-$VERSION.apk"
    echo "dist/PocketTS-$VERSION.apk is ready to attach to the GitHub release."
    ;;
  play-key)
    # Google Play signs the app with our own release key, so the Play and GitHub versions are the same app.
    # Play Console gives pepk.jar and an encryption key (.pem); pepk encrypts the release key with it, so only Google
    # can open the result. The result goes next to the .pem, to upload in Play Console.
    [ -f "$KEYSTORE" ] || { echo "No release key at $KEYSTORE"; exit 1; }
    PEPK="${2:?Usage: scripts/dev.sh play-key path/to/pepk.jar path/to/encryption_public_key.pem}"
    PEM="${3:?Usage: scripts/dev.sh play-key path/to/pepk.jar path/to/encryption_public_key.pem}"
    [ -f "$PEPK" ] && [ -f "$PEM" ] || { echo "Can't find $PEPK or $PEM"; exit 1; }
    OUT="$(cd "$(dirname "$PEM")" && pwd)"
    docker run --rm -it --user "$(id -u):$(id -g)" -e HOME=/tmp \
      -v "$(dirname "$KEYSTORE")":/keys:ro -v "$(realpath "$PEPK")":/tools/pepk.jar:ro -v "$OUT":/play \
      "$IMAGE" java -jar /tools/pepk.jar --keystore="/keys/$(basename "$KEYSTORE")" --alias=pocketts \
      --output=/play/pocketts-play-signing-key.zip --include-cert --rsa-aes-encryption \
      --encryption-key-path="/play/$(basename "$PEM")"
    echo "Upload $OUT/pocketts-play-signing-key.zip in Play Console (it's encrypted for Google), then delete it."
    ;;
  *)
    sed -n '2,22p' "$0"
    exit 1
    ;;
esac
