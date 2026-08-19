#!/bin/sh
# Put the Flix release this engine is written against into plugin/lib, and check its digest.
#
# The engine pattern-matches Flix's AST, so it is compiled against one release and links
# against that release's shape. Which release that is has to be a fact in the repository, not
# whatever jar happened to be on the machine -- otherwise "it built here" means nothing.
#
# This is the same discipline flixw applies to the compiler itself: name the version, name the
# digest, refuse anything else.
set -eu

FLIX_VERSION=0.75.3
FLIX_SHA256=bf123cdb6494d6e0cbff6399bf185314d332bbe97bfd776e4abc03a5d39dd954

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
jar=$root/plugin/lib/flix.jar
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi

if [ -f "$jar" ]; then
  # shellcheck disable=SC2086  # $sum is a command plus flags, deliberately split
  got=$($sum "$jar" | cut -d' ' -f1)
  [ "$got" = "$FLIX_SHA256" ] && exit 0
  echo "fetch-flix: plugin/lib/flix.jar is not Flix $FLIX_VERSION; replacing it" >&2
  rm -f "$jar"
fi

mkdir -p "$root/plugin/lib"
url=https://github.com/flix/flix/releases/download/v$FLIX_VERSION/flix.jar
echo "fetch-flix: downloading Flix $FLIX_VERSION" >&2
curl -fsSL -o "$jar.part" "$url" || { echo "fetch-flix: cannot download $url" >&2; exit 1; }
# shellcheck disable=SC2086
got=$($sum "$jar.part" | cut -d' ' -f1)
if [ "$got" != "$FLIX_SHA256" ]; then
  rm -f "$jar.part"
  echo "fetch-flix: digest mismatch for Flix $FLIX_VERSION" >&2
  echo "            expected $FLIX_SHA256" >&2
  echo "            actual   $got" >&2
  exit 1
fi
mv "$jar.part" "$jar"
echo "fetch-flix: Flix $FLIX_VERSION verified" >&2
