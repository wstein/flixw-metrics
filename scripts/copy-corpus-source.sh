#!/bin/sh
# Copy a local checkout of a calibration project into a scratch directory.
# The source may be a symlink to the checkout; the copy holds the files themselves (`cp -R` of the
# link alone would copy only the link), so a rewrite of the copy cannot reach the original.
# Links inside the checkout stay links. A link that points outside the copy (an absolute target, or
# a relative one that climbs above the copy's root, also through a chain of links) is refused with
# exit 2 and the copy is removed: followed by a rewrite, it would change another directory. Every
# link is resolved physically and must end under the copy.
set -eu

source=${1:?usage: sh scripts/copy-corpus-source.sh <checkout> <new-directory>}
target=${2:?usage: sh scripts/copy-corpus-source.sh <checkout> <new-directory>}

[ -d "$source" ] || { echo "copy-corpus-source: no checkout $source" >&2; exit 2; }
[ ! -e "$target" ] || { echo "copy-corpus-source: $target already exists" >&2; exit 2; }
mkdir -p "$target"
cp -R "$source/." "$target"

# Prints "<link> -> <target>" for every link in the copy that does not resolve to a place under the
# copy. Each link is followed physically (every link on the way, `..` after a link included) with
# `cd -P`; a link that cannot be resolved counts as leaving. No text comparison, no awk.
# shellcheck disable=SC1007  # Empty CDPATH is scoped to cd, not assigned to top.
top=$(CDPATH= cd -- "$target" && pwd -P)
escaping=$(find "$target" -type l -exec sh -c '
  top=$1
  shift
  for link do
    p=$link
    n=0
    while [ -L "$p" ]; do
      n=$((n + 1))
      [ "$n" -le 40 ] || break
      to=$(readlink "$p")
      case $to in
        /*) p=$to ;;
        *) p=${p%/*}/$to ;;
      esac
    done
    resolved=
    if [ -L "$p" ]; then
      :
    elif [ -d "$p" ]; then
      resolved=$(CDPATH= cd -P -- "$p" 2>/dev/null && pwd -P) || resolved=
    elif dir=$(CDPATH= cd -P -- "${p%/*}" 2>/dev/null && pwd -P); then
      resolved=$dir/${p##*/}
    fi
    case $resolved in
      "$top" | "$top"/*) ;;
      *) printf "%s -> %s\n" "$link" "$(readlink "$link")" ;;
    esac
  done' sh "$top" {} +)
if [ -n "$escaping" ]; then
  printf '%s\n' "$escaping" | while IFS= read -r line; do
    echo "copy-corpus-source: link leaves the checkout: $line" >&2
  done
  rm -rf "$target"
  exit 2
fi
