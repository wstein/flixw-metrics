#!/bin/sh
# Analyze one small checked-in project with each real Flix release from 0.78.0 on, and compare
# the totals with a golden file per release.
#
# Why: the semantic fixtures run against the pinned compiler only, and test-compiler-compatibility.sh
# checks linkage and the fixture's own counts. Neither proves that a release after the pin reports
# the same totals for a project that has a module pair, an effect with a handler, a test and a
# documented definition. Line numbers are deliberately NOT compared: from Flix 0.78 a definition
# with a doc comment starts at its `def` line, not at the doc comment (docs/compiler-compatibility).
#
# The release digests are read from the `fetch <version> <sha256>` lines of
# test-compiler-compatibility.sh, so a digest is stated once. Needs dist/plugin.jar (make package).
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sample=$root/plugin/test/fixtures/release-sample
golden=$root/plugin/test/golden
registry=$root/scripts/test-compiler-compatibility.sh
work=$root/out/compiler-compatibility
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
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi

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
mkdir -p "$work"

digest_of() {
  expected=$(awk -v v="$1" '$1 == "fetch" && $2 == v { print $3 }' "$registry")
  case $expected in
    [0-9a-f]*) [ "${#expected}" -eq 64 ] || expected= ;;
    *) expected= ;;
  esac
  [ -n "$expected" ] || {
    echo "test-flix-sample: no 64-hex digest for Flix $1 in test-compiler-compatibility.sh" >&2
    exit 1
  }
  echo "$expected"
}

fetch() {
  version=$1
  expected=$(digest_of "$version")
  jar=$work/flix-$version.jar
  if [ -f "$jar" ]; then
    # shellcheck disable=SC2086  # $sum is a command plus flags, deliberately split
    actual=$($sum "$jar" | cut -d' ' -f1)
    [ "$actual" = "$expected" ] && return
    rm -f "$jar"
  fi
  echo "test-flix-sample: downloading Flix $version" >&2
  curl -fsSL -o "$jar.part" "https://github.com/flix/flix/releases/download/v$version/flix.jar"
  # shellcheck disable=SC2086
  actual=$($sum "$jar.part" | cut -d' ' -f1)
  if [ "$actual" != "$expected" ]; then
    rm -f "$jar.part"
    echo "test-flix-sample: digest mismatch for Flix $version" >&2
    exit 1
  fi
  mv "$jar.part" "$jar"
}

for version in $versions; do
  [ -f "$golden/release-sample-$version.json" ] || {
    echo "test-flix-sample: golden file is missing: plugin/test/golden/release-sample-$version.json" >&2
    exit 1
  }
  fetch "$version"
  project=$scratch/$version/project
  mkdir -p "$project"
  cp -R "$sample/." "$project/"
  FLIXW_ABI_VERSION=1 \
  FLIXW_PROJECT_ROOT=$project \
  FLIXW_COMPILER_JAR=$work/flix-$version.jar \
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
  # Informational only: positions move between releases and are not part of the golden.
  jq -r '.definitions[] | select(.name == "Counter.silence")
    | "test-flix-sample: Flix '"$version"' Counter.silence line \(.line), \(.lines) lines (not compared)"' \
    "$scratch/$version/report.json" >&2
done

echo "test-flix-sample: totals match the golden files for Flix $versions"
