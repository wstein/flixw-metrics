#!/bin/sh
# Measure the compiler-embedded Prelude while taking line metrics from the matching checkout.
set -eu

checkout=${1:?usage: sh scripts/calibrate-prelude.sh <flix-checkout>}
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
prelude=$checkout/main/src/library/Prelude.flix

[ -f "$prelude" ] || {
  echo "calibrate-prelude: no $prelude" >&2
  exit 2
}

sh "$root/scripts/fetch-flix.sh"
mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" --no-server plugin.test.compile >/dev/null)

exec java -cp "$root/out/plugin/compile.dest/classes:$root/out/flix0753/compile.dest/classes:$root/out/plugin/test/compile.dest/classes:$root/plugin/lib/flix.jar" \
  dev.flixw.metrics.StdlibCalibration \
  "$root/plugin/test/fixtures/semantic" "$prelude" Prelude.flix
