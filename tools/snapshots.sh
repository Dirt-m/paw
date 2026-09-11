#!/usr/bin/env bash
# Renders every screen of the app on the JVM (Robolectric + Roborazzi, no
# device needed) and writes PNGs to app/snapshots/. Optionally pass extra
# gradle args, e.g. --tests 'org.paw.app.snap.ScreenSnapshots.song_portrait'.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"

./gradlew --console=plain :app:testDebugUnitTest \
  --tests 'org.paw.app.snap.*' "$@"

echo
echo "Snapshots:"
ls -1 "$(pwd)"/app/snapshots/*.png
