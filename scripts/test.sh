#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sh "$root/scripts/lint.sh"
sh "$root/scripts/package.sh" 0.0.0-test >/dev/null
java -jar "$root/dist/plugin.jar" --help | grep -q 'flixw metrics'
# The outer phase must run with no Scala on its class path at all: it is what answers when the
# engine could not link, so a scala-library it cannot find would defeat the whole split.
java -jar "$root/dist/plugin.jar" --version | grep -q 'metrics'
# The plugin shares one flat class path with the compiler. Bundling either dependency makes class
# path order decide which copy links, and packaging scratch files is release debris.
if unzip -Z1 "$root/dist/plugin.jar" | grep -Eq '^(scala/|ca/uwaterloo/flix/|manifest\.txt$)'; then
  echo 'test: packaged jar contains a forbidden dependency or scratch manifest' >&2
  exit 1
fi
mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" --no-server plugin.test.check)
