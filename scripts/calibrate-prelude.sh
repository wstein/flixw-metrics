#!/bin/sh
# Measure the compiler-embedded Prelude while taking line metrics from the matching checkout.
set -eu

checkout=${1:?usage: sh scripts/calibrate-prelude.sh <flix-checkout>}
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
prelude=$checkout/main/src/library/Prelude.flix
compiler_jar=${CALIBRATION_COMPILER_JAR:-$root/plugin/lib/flix.jar}

[ -f "$prelude" ] || {
  echo "calibrate-prelude: no $prelude" >&2
  exit 2
}

sh "$root/scripts/fetch-flix.sh"
mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" --no-server plugin.test.compile >/dev/null)

fixture=$root/plugin/test/fixtures/semantic
if [ "${CALIBRATION_REWRITE_FIXTURE:-0}" = 1 ]; then
  # The fixture keeps the pre-0.78 [package] keys for older compilers; Flix 0.78+ needs a copy.
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT INT TERM
  cp -R "$fixture" "$work/fixture"
  printf '%s\n' '{"publicModules":[]}' > "$work/spec.json"
  sh "$root/scripts/rewrite-corpus-copy.sh" "$work/fixture" "$work/spec.json" plugin/test/fixtures/semantic
  fixture=$work/fixture
fi

java -cp "$root/out/plugin/compile.dest/classes:$root/out/adapter/flix0761/compile.dest/classes:$root/out/plugin/test/compile.dest/classes:$compiler_jar" \
  dev.flixw.metrics.StdlibCalibration \
  "$fixture" "$prelude" Prelude.flix
