#!/bin/sh
# The project has no formatter dependency; reject whitespace a formatter would change.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
if git -C "$root" diff --check; then :; else exit 1; fi
if find "$root/plugin/src" "$root/plugin/test" "$root/scripts" -type f \( -name '*.java' -o -name '*.sh' \) \
  -exec grep -nE '[[:blank:]]+$' {} + | grep -q .; then
  echo 'format: trailing whitespace found' >&2
  exit 1
fi
