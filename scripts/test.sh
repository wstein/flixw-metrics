#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sh "$root/scripts/lint.sh"
sh "$root/scripts/test-calibration.sh"
sh "$root/scripts/test-performance-budget.sh"
sh "$root/scripts/package.sh" 0.0.0-test >/dev/null
first_package=$(shasum -a 256 "$root/dist/plugin.jar" | awk '{print $1}')
# ZIP timestamps have two-second precision; cross that boundary so timestamped packaging cannot
# accidentally satisfy the reproducibility contract on a fast machine.
sleep 2
sh "$root/scripts/package.sh" 0.0.0-test >/dev/null
second_package=$(shasum -a 256 "$root/dist/plugin.jar" | awk '{print $1}')
[ "$first_package" = "$second_package" ] || {
  echo 'test: identical package inputs produced different plugin jars' >&2
  exit 1
}
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
sh "$root/scripts/test-compiler-compatibility.sh"
mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" --no-server plugin.test.check)
