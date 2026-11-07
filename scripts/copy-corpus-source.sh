#!/bin/sh
# Copy a local checkout of a calibration project into a scratch directory.
# The source may be a symlink to the checkout; the copy holds the files themselves (`cp -R` of the
# link alone would copy only the link), so a rewrite of the copy cannot reach the original.
set -eu

source=${1:?usage: sh scripts/copy-corpus-source.sh <checkout> <new-directory>}
target=${2:?usage: sh scripts/copy-corpus-source.sh <checkout> <new-directory>}

[ -d "$source" ] || { echo "copy-corpus-source: no checkout $source" >&2; exit 2; }
[ ! -e "$target" ] || { echo "copy-corpus-source: $target already exists" >&2; exit 2; }
mkdir -p "$target"
cp -R "$source/." "$target"
