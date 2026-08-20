#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sh "$root/scripts/lint.sh"
sh "$root/scripts/package.sh" 0.0.0-test >/dev/null
java -jar "$root/dist/plugin.jar" --help | grep -q 'plugin flixw-metrics'
# The outer phase must run with no Scala on its class path at all: it is what answers when the
# engine could not link, so a scala-library it cannot find would defeat the whole split.
java -jar "$root/dist/plugin.jar" --version | grep -q 'flixw-metrics'
mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" plugin.test.check)
