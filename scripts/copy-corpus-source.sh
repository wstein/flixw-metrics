#!/bin/sh
# Copy a local checkout of a calibration project into a scratch directory.
# The source may be a symlink to the checkout; the copy holds the files themselves (`cp -R` of the
# link alone would copy only the link), so a rewrite of the copy cannot reach the original.
# Links inside the checkout stay links. A link that points outside the copy (an absolute target, or
# a relative one that climbs above the copy's root) is refused with exit 2 and the copy is removed:
# followed by a rewrite, it would change another directory. Relative targets are judged by their
# path text, not by resolving links on the way.
set -eu

source=${1:?usage: sh scripts/copy-corpus-source.sh <checkout> <new-directory>}
target=${2:?usage: sh scripts/copy-corpus-source.sh <checkout> <new-directory>}

[ -d "$source" ] || { echo "copy-corpus-source: no checkout $source" >&2; exit 2; }
[ ! -e "$target" ] || { echo "copy-corpus-source: $target already exists" >&2; exit 2; }
mkdir -p "$target"
cp -R "$source/." "$target"

# Prints "<link> -> <target>" for every link in the copy that leaves it.
escaping=$(find "$target" -type l -exec sh -c '
  top=$1
  shift
  for link do
    to=$(readlink "$link")
    case $to in
      /*) printf "%s -> %s\n" "$link" "$to"; continue ;;
    esac
    dir=$(dirname "$link")
    awk -v dir="${dir#"$top"}" -v to="$to" '"'"'BEGIN {
      n = split(dir "/" to, part, "/")
      depth = 0
      for (i = 1; i <= n; i++) {
        if (part[i] == "" || part[i] == ".") continue
        if (part[i] == "..") { if (--depth < 0) exit 1 } else depth++
      }
    }'"'"' || printf "%s -> %s\n" "$link" "$to"
  done' sh "$target" {} +)
if [ -n "$escaping" ]; then
  printf '%s\n' "$escaping" | while IFS= read -r line; do
    echo "copy-corpus-source: link leaves the checkout: $line" >&2
  done
  rm -rf "$target"
  exit 2
fi
