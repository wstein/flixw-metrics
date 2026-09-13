#!/bin/sh
# Compile once, then run one dependency-free test main with any required fixture/classpath wiring.
set -eu

test_name=${1:?usage: sh scripts/test-one.sh TestName}
[ "$#" -eq 1 ] || { echo 'usage: sh scripts/test-one.sh TestName' >&2; exit 2; }
case $test_name in
  *[!A-Za-z0-9._$]*) echo "test-one: invalid test name: $test_name" >&2; exit 2 ;;
esac
case $test_name in
  *.*) class=$test_name ;;
  *) class=dev.flixw.metrics.$test_name ;;
esac

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" --no-server plugin.test.compile >/dev/null)
classpath=$(cd "$root" && "$mill" --no-server show plugin.test.runClasspath 2>/dev/null \
  | jq -r '.[] | split(":")[-1]' | paste -sd: -)

case $class in
  dev.flixw.metrics.CompilerCapabilitiesTest)
    exec java -cp "$classpath" "$class" "$root/plugin/lib/flix.jar" \
      "$root/plugin/lib/flix-0.68.0.jar" "$root/plugin/lib/flix-0.67.2.jar" \
      "$root/plugin/lib/flix-0.66.1.jar"
    ;;
  dev.flixw.metrics.Flix0661AdapterTest)
    exec java -cp "$classpath:$root/plugin/lib/flix-0.66.1.jar" "$class" \
      "$root/plugin/test/fixtures/semantic"
    ;;
  dev.flixw.metrics.Flix0672AdapterTest)
    exec java -cp "$classpath:$root/plugin/lib/flix-0.67.2.jar" "$class" \
      "$root/plugin/test/fixtures/semantic"
    ;;
  dev.flixw.metrics.Flix0680AdapterTest)
    exec java -cp "$classpath:$root/plugin/lib/flix-0.68.0.jar" "$class" \
      "$root/plugin/test/fixtures/semantic"
    ;;
  dev.flixw.metrics.Flix0753AdapterTest)
    exec java -cp "$classpath:$root/plugin/lib/flix.jar" "$class" \
      "$root/plugin/test/fixtures/semantic"
    ;;
  dev.flixw.metrics.PluginIntegrationTest)
    sh "$root/scripts/package.sh" 0.0.0-test >/dev/null
    exec java -cp "$classpath:$root/plugin/lib/flix.jar" "$class" \
      "$root/plugin/test/fixtures/semantic" "$root/dist/plugin.jar" "$root/plugin/lib/flix.jar"
    ;;
  *) exec java -cp "$classpath" "$class" ;;
esac
