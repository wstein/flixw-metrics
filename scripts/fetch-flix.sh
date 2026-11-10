#!/bin/sh
# Put the Flix releases this build is written against into plugin/lib, and check their digests.
#
# Usage: sh scripts/fetch-flix.sh            the floor and test release of every adapter family,
#                                            and flix.jar
#        sh scripts/fetch-flix.sh all        every release in versions.json, rejected ones included
#        sh scripts/fetch-flix.sh <version>  only that release
# Environment (all optional, for tests): FLIXW_VERSIONS_JSON, FLIXW_PLUGIN_LIB (default plugin/lib),
# FLIXW_FLIX_BASE_URL (default https://github.com/flix/flix/releases/download).
#
# Which releases exist, and the SHA-256 of each, is versions.json of the pinned SDK
# (scripts/sdk-versions.sh); nothing is listed here. Each adapter family is compiled against the
# oldest release of its family (its floor) and tested against its test release. plugin/lib/flix.jar
# is a copy of the newest family's test release, the pin for the tests and the calibration.
# Which releases these are has to be a fact in the repository, not whatever jar happened to be on
# the machine -- otherwise "it built here" means nothing. This is the discipline flixw applies to
# the compiler itself: name the version, name the digest, refuse anything else.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
lib=${FLIXW_PLUGIN_LIB:-$root/plugin/lib}
base=${FLIXW_FLIX_BASE_URL:-https://github.com/flix/flix/releases/download}
versions() { sh "$root/scripts/sdk-versions.sh" "$@"; }
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi
# shellcheck disable=SC2086  # $sum is a command plus flags, deliberately split
digest() { $sum "$1" | cut -d' ' -f1; }

pin=
case ${1-} in
  '')
    # Floor and test release of every family; the newest family comes first in the data.
    wanted=
    for family in $(versions families); do
      floor=$(versions floor "$family")
      test_version=$(jq -er --arg f "$family" '.families[] | select(.id == $f) | .testVersion' "$(versions path)")
      wanted="$wanted $floor $test_version"
    done
    wanted=$(printf '%s\n' $wanted | sort -u)
    pin=$(jq -er '.families[0].testVersion' "$(versions path)")
    ;;
  all)
    wanted=$(versions all)
    pin=$(jq -er '.families[0].testVersion' "$(versions path)")
    ;;
  *) wanted=$* ;;
esac

mkdir -p "$lib"
for version in $wanted; do
  expected=$(versions digest "$version" 2>/dev/null) || {
    echo "fetch-flix: $version is not in versions.json" >&2
    exit 1
  }
  jar=$lib/flix-$version.jar
  if [ -f "$jar" ]; then
    [ "$(digest "$jar")" = "$expected" ] && continue
    echo "fetch-flix: $jar is not Flix $version; replacing it" >&2
    rm -f "$jar"
  fi
  echo "fetch-flix: downloading Flix $version" >&2
  curl -fsSL --retry 3 --retry-delay 5 --connect-timeout 30 --max-time 600 -o "$jar.part" \
    "$base/v$version/flix.jar" || {
    rm -f "$jar.part"
    echo "fetch-flix: cannot download Flix $version" >&2
    exit 1
  }
  got=$(digest "$jar.part")
  if [ "$got" != "$expected" ]; then
    rm -f "$jar.part"
    echo "fetch-flix: digest mismatch for Flix $version" >&2
    echo "            expected $expected" >&2
    echo "            actual   $got" >&2
    exit 1
  fi
  mv "$jar.part" "$jar"
  echo "fetch-flix: Flix $version verified" >&2
done

if [ -n "$pin" ]; then
  if [ ! -f "$lib/flix.jar" ] || [ "$(digest "$lib/flix.jar")" != "$(versions digest "$pin")" ]; then
    cp "$lib/flix-$pin.jar" "$lib/flix.jar.part"
    mv "$lib/flix.jar.part" "$lib/flix.jar"
    echo "fetch-flix: flix.jar is Flix $pin" >&2
  fi
fi
