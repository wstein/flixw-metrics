#!/bin/sh
# Verify the exact local source dependency before compiling any plugin or SDK source.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
pin=${1:-$root/sdk-source.properties}
fail() { printf 'sdk-source: %s\n' "$*" >&2; exit 2; }
[ -f "$pin" ] || fail "missing pin file: $pin"
property() {
  awk -v key="$1" '
    { sub(/\r$/, "") }
    index($0, key "=") == 1 { value = substr($0, length(key) + 2); count++ }
    END { if (count == 1) print value; else exit 1 }
  ' "$pin" || fail "expected one $1 property"
}
repository=$(property repository)
revision=$(property revision)
module=$(property module)
[ "$repository" = 'https://github.com/wstein/flixw-plugin-sdk' ] || fail 'unsupported repository in pin'
[ "$module" = kit ] || fail 'unsupported module in pin (expected kit)'
[ "${#revision}" -eq 40 ] || fail 'revision must be a full 40-character lowercase commit SHA'
case $revision in *[!0-9a-f]*) fail 'revision must be a full 40-character lowercase commit SHA' ;; esac
[ -n "${FLIXW_SDK_SOURCE:-}" ] || fail 'set FLIXW_SDK_SOURCE to the pinned SDK checkout'
[ -d "$FLIXW_SDK_SOURCE" ] || fail "FLIXW_SDK_SOURCE is not a directory: $FLIXW_SDK_SOURCE"
source=$(CDPATH= cd -- "$FLIXW_SDK_SOURCE" && pwd -P)
top=$(git -C "$source" rev-parse --show-toplevel 2>/dev/null) || fail 'FLIXW_SDK_SOURCE is not a Git checkout'
[ "$top" = "$source" ] || fail 'FLIXW_SDK_SOURCE must name the checkout root'
head=$(git -C "$source" rev-parse HEAD) || fail 'cannot read SDK HEAD'
[ "$head" = "$revision" ] || fail "SDK HEAD is $head, expected $revision"
status=$(git -C "$source" status --porcelain --untracked-files=all) || fail 'cannot inspect SDK source changes'
[ -z "$status" ] || fail 'SDK sources have changed (tracked or untracked files); use a clean pinned checkout'
[ -d "$source/kit/src" ] || fail 'SDK kit/src is missing'
[ -d "$source/kit/resources" ] || fail 'SDK kit/resources is missing'
printf '%s\n' "$source"
