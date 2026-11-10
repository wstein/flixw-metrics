#!/bin/sh
# Analyze one small checked-in project with each real Flix release from 0.78.0 on, and compare
# the totals with a golden file per release.
#
# Why: the semantic fixtures run against the pinned compiler only, and test-compiler-compatibility.sh
# checks linkage, the fixture's own counts and one span project against 0.77.0. None proves that
# a release after the pin reports the same totals for a project that has a module pair, an effect
# with a handler, a test and a documented definition. Positions are kept out of the totals golden
# and pinned separately, per release: Flix 0.79.0 starts a documented definition at its `def`
# line, and Flix0761Adapter restores the doc-comment start, so both releases are pinned to the
# same position (flix-bridge/docs/compatibility/flix-0.79.0.md in the SDK).
#
# The releases and their digests are versions.json of the pinned SDK, fetched into plugin/lib by
# scripts/fetch-flix.sh, so a digest is stated once. Needs dist/plugin.jar (make package).
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sample=$root/plugin/test/fixtures/release-sample
golden=$root/plugin/test/golden
lib=$root/plugin/lib
versions="0.78.0 0.79.0"

for command in curl java jq diff; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "test-flix-sample: $command is required" >&2
    exit 2
  }
done
[ -f "$root/dist/plugin.jar" ] || {
  echo "test-flix-sample: dist/plugin.jar is missing; run scripts/package.sh first" >&2
  exit 2
}

java_home=${JAVA_HOME:-}
if [ -z "$java_home" ]; then
  java_home=$(dirname "$(dirname "$(command -v java)")")
fi
[ -x "$java_home/bin/java" ] || {
  echo "test-flix-sample: no java executable under $java_home" >&2
  exit 2
}

scratch=$(mktemp -d "${TMPDIR:-/tmp}/flixw-metrics-sample.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

fetch() {
  sh "$root/scripts/fetch-flix.sh" "$1" >&2
}

for version in $versions; do
  for name in release-sample release-sample-positions; do
    [ -f "$golden/$name-$version.json" ] || {
      echo "test-flix-sample: golden file is missing: plugin/test/golden/$name-$version.json" >&2
      exit 1
    }
  done
  fetch "$version"
  project=$scratch/$version/project
  mkdir -p "$project"
  cp -R "$sample/." "$project/"
  FLIXW_ABI_VERSION=1 \
  FLIXW_PROJECT_ROOT=$project \
  FLIXW_COMPILER_JAR=$lib/flix-$version.jar \
  FLIXW_JAVA_HOME=$java_home \
    "$java_home/bin/java" -jar "$root/dist/plugin.jar" report --format json --output "$scratch/$version/report.json" > "$scratch/$version/stdout.txt" \
    2> "$scratch/$version/stderr.txt" || {
      echo "test-flix-sample: the plugin failed on Flix $version" >&2
      cat "$scratch/$version/stderr.txt" >&2
      exit 1
    }
  jq -S '{
      files: .summary.files, modules: .summary.modules, definitions: .summary.definitions,
      effects: .summary.effects, tests: .summary.tests, cognitive: .summary.cognitive,
      effectPolymorphicDefinitions: .summary.effectPolymorphicDefinitions,
      smells: (.smells | length), smellRules: ([.smells[].rule] | sort)
    }' "$scratch/$version/report.json" > "$scratch/$version/actual.json"
  jq -S . "$golden/release-sample-$version.json" > "$scratch/$version/expected.json"
  diff -u "$scratch/$version/expected.json" "$scratch/$version/actual.json" >&2 || {
    echo "test-flix-sample: Flix $version totals differ from plugin/test/golden/release-sample-$version.json" >&2
    exit 1
  }
  # Positions are pinned apart from the totals, per release. Flix 0.79.0 starts a documented
  # definition at its `def` line; the adapter restores the doc-comment start, so the 0.78.0 and
  # 0.79.0 goldens are equal. A golden change here is a decision about positions, not a totals
  # regression.
  jq -S '[.definitions[] | select(.name == "Counter.silence") | {name, line, lines}][0]' \
    "$scratch/$version/report.json" > "$scratch/$version/actual-positions.json"
  jq -S . "$golden/release-sample-positions-$version.json" > "$scratch/$version/expected-positions.json"
  diff -u "$scratch/$version/expected-positions.json" "$scratch/$version/actual-positions.json" >&2 || {
    echo "test-flix-sample: Flix $version positions differ from plugin/test/golden/release-sample-positions-$version.json" >&2
    exit 1
  }
done

echo "test-flix-sample: totals and positions match the golden files for Flix $versions"
