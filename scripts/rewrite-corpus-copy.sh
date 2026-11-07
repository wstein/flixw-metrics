#!/bin/sh
# Rewrite a checked-out calibration project so Flix 0.78 and later can load it.
# [package] keeps only version, repository and flix; a `mod X {` line named in the spec becomes
# `pub mod X {`; a `renames` entry moves a file so its name matches its module. The checkout is
# a scratch copy; nothing is written outside it.
set -eu

project=${1:?usage: sh scripts/rewrite-corpus-copy.sh <project-directory> <spec.json> <origin-note>}
spec=${2:?usage: sh scripts/rewrite-corpus-copy.sh <project-directory> <spec.json> <origin-note>}
origin=${3:?usage: sh scripts/rewrite-corpus-copy.sh <project-directory> <spec.json> <origin-note>}

command -v jq >/dev/null 2>&1 || {
  echo "rewrite-corpus-copy: jq is required" >&2
  exit 2
}
[ -d "$project" ] || { echo "rewrite-corpus-copy: no project $project" >&2; exit 2; }
[ -f "$spec" ] || { echo "rewrite-corpus-copy: no spec $spec" >&2; exit 2; }

manifest=$project/flix.toml
[ -f "$manifest" ] || { echo "rewrite-corpus-copy: no $manifest" >&2; exit 2; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

# Flix 0.78+ rejects every other [package] key (IllegalPackageKeyFound).
awk -v origin="$origin" '
  /^[ \t]*\[/ { in_package = ($0 ~ /^[ \t]*\[package\][ \t]*(#.*)?$/) }
  in_package && /^[ \t]*[A-Za-z_-]+[ \t]*=/ {
    key = $0
    sub(/^[ \t]*/, "", key)
    sub(/[ \t]*=.*/, "", key)
    if (key != "version" && key != "repository" && key != "flix") {
      removed = removed (removed == "" ? "" : ", ") key
      next
    }
  }
  { lines[++n] = $0 }
  END {
    for (i = 1; i <= n; i++) {
      print lines[i]
      if (!noted && lines[i] ~ /^[ \t]*\[package\][ \t]*(#.*)?$/) {
        print "# corpus-0.78 rewrite of " origin ": removed " (removed == "" ? "nothing" : removed)
        noted = 1
      }
    }
  }
' "$manifest" > "$work/flix.toml"
# The project must be a scratch copy (scripts/copy-corpus-source.sh makes one): this script edits,
# renames and removes files inside it, also through a symlinked directory.
# Replacing the file (not writing through it) only keeps a hard-linked file of a copy intact.
cat "$work/flix.toml" > "$work/flix.toml.new"
rm -f "$manifest"
mv "$work/flix.toml.new" "$manifest"

safe_source() {
  case "$1" in
    src/*.flix) ;;
    *) echo "rewrite-corpus-copy: unsafe path $1" >&2; exit 2 ;;
  esac
  case "$1" in
    *..*) echo "rewrite-corpus-copy: unsafe path $1" >&2; exit 2 ;;
  esac
}

renames=$(jq -r '(.renames // []) | length' "$spec")
index=0
while [ "$index" -lt "$renames" ]; do
  from=$(jq -r ".renames[$index].from" "$spec")
  to=$(jq -r ".renames[$index].to" "$spec")
  index=$((index + 1))
  safe_source "$from"
  safe_source "$to"
  [ -f "$project/$from" ] || { echo "rewrite-corpus-copy: no source $from" >&2; exit 2; }
  [ ! -e "$project/$to" ] || { echo "rewrite-corpus-copy: $to already exists" >&2; exit 2; }
  mkdir -p "$(dirname "$project/$to")"
  mv "$project/$from" "$project/$to"
done

count=$(jq -r '.publicModules | length' "$spec")
index=0
while [ "$index" -lt "$count" ]; do
  file=$(jq -r ".publicModules[$index].file" "$spec")
  module=$(jq -r ".publicModules[$index].module" "$spec")
  index=$((index + 1))
  safe_source "$file"
  case "$module" in
    *[!A-Za-z0-9_]*|"") echo "rewrite-corpus-copy: unsafe module $module" >&2; exit 2 ;;
  esac
  target=$project/$file
  [ -f "$target" ] || { echo "rewrite-corpus-copy: no source $file" >&2; exit 2; }
  matches=$(grep -c "^mod $module {\$" "$target" || true)
  [ "$matches" -eq 1 ] || {
    echo "rewrite-corpus-copy: no unique \"mod $module {\" line in $file ($matches)" >&2
    exit 2
  }
  sed "s/^mod $module {\$/pub mod $module {/" "$target" > "$work/source.new"
  rm -f "$target"
  mv "$work/source.new" "$target"
done
