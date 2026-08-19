#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
sh "$root/scripts/lint.sh"
sh "$root/scripts/package.sh" 0.0.0-test >/dev/null
java -jar "$root/dist/plugin.jar" --help | grep -q 'plugin flixw-metrics'
javac -Xlint:all -Werror -cp "$root/dist/plugin.jar" -d "$work" \
  "$root/test/java/dev/flixw/metrics/CompilerCapabilitiesTest.java" \
  "$root/test/java/dev/flixw/metrics/ResultCacheTest.java"
java -cp "$root/dist/plugin.jar:$work" dev.flixw.metrics.CompilerCapabilitiesTest
java -cp "$root/dist/plugin.jar:$work" dev.flixw.metrics.ResultCacheTest
