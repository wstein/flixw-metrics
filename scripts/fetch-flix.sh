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

FLIX_VERSION=0.76.1
FLIX_SHA256=b2ed2b7f49902e2dfe1b4f10e23d9a5cb085461e7e8c64909ca2b3abfa566e74
FLIX_0753_VERSION=0.75.3
FLIX_0753_SHA256=bf123cdb6494d6e0cbff6399bf185314d332bbe97bfd776e4abc03a5d39dd954
FLIX_0760_VERSION=0.76.0
FLIX_0760_SHA256=d8d9a3870e199c03ed6364ea9430f56f67bfd38c332c411628a6a7cb88b2b0b4
FLIX_0680_VERSION=0.68.0
FLIX_0680_SHA256=af568a2d4046207f908f8ec37786409b3dccdec944c2df5f571444599fb6b7c8
FLIX_0672_VERSION=0.67.2
FLIX_0672_SHA256=3162ba033d77e481c8cd731441e1f279bd8f44e5f278b2b21632149c30f8ed3f
FLIX_0661_VERSION=0.66.1
FLIX_0661_SHA256=71b46d37d9c2e24b4eabd67ed2491a33adbb1a039e7bc49ef1f7556870e6344d
FLIX_0610_VERSION=0.61.0
FLIX_0610_SHA256=e024cd8a72d52d3553cf7fb6b18cb81e1315ad524d9bfab89e317d63dda04ab9
FLIX_0600_VERSION=0.60.0
FLIX_0600_SHA256=9920b107c9b4eb42bf17c6f556141b4cc8edbfffdf792de9a1bc5284505cd3c5

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
fetch "$FLIX_0753_VERSION" "$FLIX_0753_SHA256" "$root/plugin/lib/flix-0.75.3.jar"
fetch "$FLIX_0760_VERSION" "$FLIX_0760_SHA256" "$root/plugin/lib/flix-0.76.0.jar"
fetch "$FLIX_0680_VERSION" "$FLIX_0680_SHA256" "$root/plugin/lib/flix-0.68.0.jar"
fetch "$FLIX_0672_VERSION" "$FLIX_0672_SHA256" "$root/plugin/lib/flix-0.67.2.jar"
fetch "$FLIX_0661_VERSION" "$FLIX_0661_SHA256" "$root/plugin/lib/flix-0.66.1.jar"
fetch "$FLIX_0610_VERSION" "$FLIX_0610_SHA256" "$root/plugin/lib/flix-0.61.0.jar"
fetch "$FLIX_0600_VERSION" "$FLIX_0600_SHA256" "$root/plugin/lib/flix-0.60.0.jar"
