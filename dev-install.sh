#!/usr/bin/env bash
#
# dev-install.sh — install this shared build into your local ~/.m2 so consumers (the SDK, the Studio,
# and generated bots) pick up local changes WITHOUT pushing a git tag.
#
# Unlike the SDK, botmaker-shared has no coordinate mismatch to work around: its groupId is already
# com.github.LiQiyeDev (the coordinate JitPack serves), so a plain `mvn install` lands it at
# 0.0.0-SNAPSHOT — the exact version the sdk/studio poms default to (${botmaker.shared.version}) and
# that the SDK's own local-SNAPSHOT dev build references transitively. So there is no version-renaming
# trick here; this script just builds + installs it (and gives the SDK dev flow a matching entry point).
#
# Usage:
#   ./dev-install.sh
#
# After this, anything resolving the default ${botmaker.shared.version}=0.0.0-SNAPSHOT — the umbrella
# reactor, a standalone Studio build, and a bot depending on the SDK's local-SNAPSHOT build — sees your
# local changes immediately. Users select real released versions and never resolve 0.0.0-SNAPSHOT.

set -euo pipefail

SHARED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> installing com.github.LiQiyeDev:botmaker-shared:0.0.0-SNAPSHOT into ~/.m2"
mvn -q -f "$SHARED_DIR/pom.xml" install -DskipTests

echo
echo "Done. Consumers on the default \${botmaker.shared.version}=0.0.0-SNAPSHOT resolve your build now."
echo "(installed at ~/.m2/repository/com/github/LiQiyeDev/botmaker-shared/0.0.0-SNAPSHOT/)"
