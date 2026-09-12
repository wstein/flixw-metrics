#!/bin/sh
# Repeated end-to-end cold and warm measurements of the packaged semantic fixture.
set -eu

output=${1:?usage: sh scripts/measure-performance.sh <measurements.json>}
# shellcheck disable=SC1007  # Empty CDPATH is scoped to cd, not assigned to root.
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
budget=$root/calibration/performance-budget.json

for command in jq java perl; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "measure-performance: $command is required" >&2
    exit 2
  }
done
samples=$(jq -r '.samples' "$budget")
java_home=${PERFORMANCE_JAVA_HOME:-${JAVA_HOME:-}}
if [ -z "$java_home" ]; then
  java_home=$(dirname "$(dirname "$(command -v java)")")
fi
[ -x "$java_home/bin/java" ] || {
  echo "measure-performance: no java executable under $java_home" >&2
  exit 2
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
project=$root/plugin/test/fixtures/semantic
plugin=$root/dist/plugin.jar
compiler=$root/plugin/lib/flix.jar

sh "$root/scripts/package.sh" 0.0.0-performance >/dev/null

now_ms() {
  perl -MTime::HiRes=time -e 'printf "%.0f\n", time() * 1000'
}

run() {
  cache=$1
  selected_java_home=$2
  start=$(now_ms)
  FLIXW_ABI_VERSION=1 \
  FLIXW_PROJECT_ROOT=$project \
  FLIXW_COMPILER_JAR=$compiler \
  FLIXW_JAVA_HOME=$selected_java_home \
  FLIXW_PLUGIN_CACHE=$cache \
    "$java_home/bin/java" -jar "$plugin" report --format json > "$work/report.json"
  end=$(now_ms)
  echo $((end - start))
}

: > "$work/cold"
i=1
while [ "$i" -le "$samples" ]; do
  echo "measure-performance: cold sample $i/$samples" >&2
  run "$work/cold-cache-$i" "$java_home" >> "$work/cold"
  i=$((i + 1))
done

warm_cache=$work/warm-cache
run "$warm_cache" "$java_home" >/dev/null
: > "$work/warm"
i=1
while [ "$i" -le "$samples" ]; do
  echo "measure-performance: warm sample $i/$samples" >&2
  run "$warm_cache" "$work/no-such-java-home" >> "$work/warm"
  i=$((i + 1))
done

middle=$((samples / 2 + 1))
cold_median=$(sort -n "$work/cold" | sed -n "${middle}p")
warm_median=$(sort -n "$work/warm" | sed -n "${middle}p")
ratio=$((warm_median * 100 / cold_median))
cold_json=$(jq -Rsc 'split("\n") | map(select(length > 0) | tonumber)' "$work/cold")
warm_json=$(jq -Rsc 'split("\n") | map(select(length > 0) | tonumber)' "$work/warm")

jq -n \
  --argjson cold "$cold_json" \
  --argjson warm "$warm_json" \
  --argjson coldMedian "$cold_median" \
  --argjson warmMedian "$warm_median" \
  --argjson ratio "$ratio" \
  '{schemaVersion: 1, coldMs: $cold, warmMs: $warm,
    coldMedianMs: $coldMedian, warmMedianMs: $warmMedian,
    warmToColdPercent: $ratio}' > "$output"

sh "$root/scripts/check-performance-budget.sh" "$output" "$budget"
echo "measure-performance: cold ${cold_median}ms; warm ${warm_median}ms; ratio ${ratio}%" >&2
