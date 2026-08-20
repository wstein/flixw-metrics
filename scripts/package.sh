#!/bin/sh
# Build plugin.jar with mill.
#
# The jar holds this module's classes and nothing else. scala-library is deliberately not
# bundled: the compiler jar already carries 2.13, and the bridge phase puts both on one flat
# class path, so a second copy would let ordering decide which one the engine links against.
set -eu

version=${1:?usage: sh scripts/package.sh <version>}
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
dist=$root/dist

sh "$root/scripts/fetch-flix.sh"

mkdir -p "$dist"
# ./mill over a system mill: the wrapper downloads the version pinned in .mill-version, so a
# release built on a CI runner and one built here are the same build. A system mill is
# whatever that machine happens to have.
mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" plugin.jar >/dev/null)

# `mill show` prints `<content-hash>:<path>`; the path is everything from the first slash.
built=$(cd "$root" && "$mill" show plugin.jar 2>/dev/null | tr -d '"' | sed 's|^[^/]*||')
[ -f "$built" ] || { echo "package: mill produced no jar at $built" >&2; exit 1; }

# The version is stamped here rather than in build.mill so a release is one argument, not an
# edit; mill's own jar carries no Implementation-Version of its own.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
(cd "$work" && unzip -qo "$built")
printf 'Main-Class: dev.flixw.metrics.Main\nImplementation-Version: %s\n' "$version" \
  > "$work/manifest.txt"
rm -rf "$work/META-INF"
(cd "$work" && jar --create --file "$dist/plugin.jar" --manifest manifest.txt .)

(cd "$dist" && shasum -a 256 plugin.jar > SHA256SUMS)
printf 'built %s/plugin.jar (%s)\n' "$dist" "$version"
