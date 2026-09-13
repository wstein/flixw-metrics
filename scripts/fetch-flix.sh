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

FLIX_VERSION=0.76.0
FLIX_SHA256=d8d9a3870e199c03ed6364ea9430f56f67bfd38c332c411628a6a7cb88b2b0b4
FLIX_0680_VERSION=0.68.0
FLIX_0680_SHA256=af568a2d4046207f908f8ec37786409b3dccdec944c2df5f571444599fb6b7c8

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi

mkdir -p "$root/plugin/lib"

fetch() {
  version=$1
  expected=$2
  jar=$3
  if [ -f "$jar" ]; then
    # shellcheck disable=SC2086  # $sum is a command plus flags, deliberately split
    got=$($sum "$jar" | cut -d' ' -f1)
    [ "$got" = "$expected" ] && return
    echo "fetch-flix: $jar is not Flix $version; replacing it" >&2
    rm -f "$jar"
  fi
  url=https://github.com/flix/flix/releases/download/v$version/flix.jar
  echo "fetch-flix: downloading Flix $version" >&2
  curl -fsSL -o "$jar.part" "$url" || { echo "fetch-flix: cannot download $url" >&2; exit 1; }
  # shellcheck disable=SC2086
  got=$($sum "$jar.part" | cut -d' ' -f1)
  if [ "$got" != "$expected" ]; then
    rm -f "$jar.part"
    echo "fetch-flix: digest mismatch for Flix $version" >&2
    echo "            expected $expected" >&2
    echo "            actual   $got" >&2
    exit 1
  fi
  mv "$jar.part" "$jar"
  echo "fetch-flix: Flix $version verified" >&2
}

fetch "$FLIX_VERSION" "$FLIX_SHA256" "$root/plugin/lib/flix.jar"
fetch "$FLIX_0680_VERSION" "$FLIX_0680_SHA256" "$root/plugin/lib/flix-0.68.0.jar"
