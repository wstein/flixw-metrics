#!/bin/sh
# Print release data from versions.json of the pinned SDK, the one place a Flix release is named.
#
# Usage: sh scripts/sdk-versions.sh verified|rejected|all|families|path
#        sh scripts/sdk-versions.sh digest|family|floor <release or family>
#
# Versions are listed oldest first. The file is read from the guarded SDK checkout
# (scripts/check-sdk-source.sh); FLIXW_VERSIONS_JSON names another document for tests.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
command -v jq >/dev/null 2>&1 || { echo 'sdk-versions: jq is required' >&2; exit 2; }
json=${FLIXW_VERSIONS_JSON:-}
if [ -z "$json" ]; then
  source=$(sh "$root/scripts/check-sdk-source.sh")
  json=$source/flix-bridge/resources/dev/flixw/sdk/bridge/versions.json
fi
[ -f "$json" ] || { echo "sdk-versions: no version data at $json" >&2; exit 1; }
case ${1-} in
  verified) jq -r '.releases[] | select(.status == "verified") | .version' "$json" ;;
  rejected) jq -r '.releases[] | select(.status == "rejected") | .version' "$json" ;;
  all) jq -r '.releases[].version' "$json" ;;
  families) jq -r '.families[].id' "$json" ;;
  path) printf '%s\n' "$json" ;;
  digest) jq -er --arg v "${2:?release}" '.releases[] | select(.version == $v) | .sha256' "$json" ;;
  family) jq -er --arg v "${2:?release}" '.releases[] | select(.version == $v) | .family' "$json" ;;
  floor) jq -er --arg f "${2:?family}" '.families[] | select(.id == $f) | .floor' "$json" ;;
  *) echo 'usage: sh scripts/sdk-versions.sh verified|rejected|all|families|path|digest <v>|family <v>|floor <family>' >&2
     exit 2 ;;
esac
