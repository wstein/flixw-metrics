#!/bin/sh
# Lock the oldest supported Flix release to 0.60.0, with its rejected predecessor as proof.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$root/out/compiler-compatibility
mkdir -p "$work"

for command in curl java jq; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "test-compiler-compatibility: $command is required" >&2
    exit 2
  }
done

# Every release versions.json names, rejected ones included, is fetched and digest-checked into
# plugin/lib; the same jars serve the other scripts and the tests.
sh "$root/scripts/fetch-flix.sh" all
lib=$root/plugin/lib

# versions.json of the pinned SDK is the one place a verified release is named. Every verified
# release needs a packaged-integration checkpoint below, and every checkpoint a verified release. The rejected releases are the ones this script proves are refused.
verified=$(sh "$root/scripts/sdk-versions.sh" verified)
[ -n "$verified" ] || {
  echo "test-compiler-compatibility: no verified releases in the pinned versions.json" >&2
  exit 1
}
[ "$(sh "$root/scripts/sdk-versions.sh" rejected | tr '\n' ' ')" = '0.59.0 0.66.0 ' ] || {
  echo "test-compiler-compatibility: the rejected releases in versions.json are not 0.59.0 and 0.66.0" >&2
  exit 1
}
for version in $verified; do
  grep -q "^integration \"\$lib/flix-$version.jar\"" "$0" || {
    echo "test-compiler-compatibility: verified release $version has no integration checkpoint" >&2
    exit 1
  }
done
for version in $(grep -o '^integration "\$lib/flix-[0-9.]*\.jar"' "$0" | sed 's/.*flix-\(.*\)\.jar".*/\1/'); do
  printf '%s\n' "$verified" | grep -qx "$version" || {
    echo "test-compiler-compatibility: integration checkpoint for $version is not in the verified list" >&2
    exit 1
  }
done

java_home=${JAVA_HOME:-}
if [ -z "$java_home" ]; then
  java_home=$(dirname "$(dirname "$(command -v java)")")
fi
[ -x "$java_home/bin/java" ] || {
  echo "test-compiler-compatibility: no java executable under $java_home" >&2
  exit 2
}

capabilities() {
  compiler=$1
  FLIXW_ABI_VERSION=1 \
  FLIXW_PROJECT_ROOT=$root/plugin/test/fixtures/semantic \
  FLIXW_COMPILER_JAR=$compiler \
  FLIXW_JAVA_HOME=$java_home \
    "$java_home/bin/java" -jar "$root/dist/plugin.jar" capabilities
}

capabilities "$lib/flix-0.66.0.jar" > "$work/0.66.0.json"
jq -e '
  (.hasEngineApi == false) and
  any(.missing[]; contains("dev.flix.runtime.Global"))
' "$work/0.66.0.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.66.0 did not fail its Java runtime boundary" >&2
  exit 1
}

capabilities "$lib/flix-0.59.0.jar" > "$work/0.59.0.json"
jq -e '
  (.hasEngineApi == false) and
  any(.missing[]; contains("Symbol$EffSym"))
' "$work/0.59.0.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.59.0 did not fail the expected AST boundary" >&2
  exit 1
}

capabilities "$lib/flix-0.60.0.jar" > "$work/0.60.0.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.60.0.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.60.0 did not satisfy the adapter ABI" >&2
  exit 1
}

capabilities "$lib/flix-0.66.1.jar" > "$work/0.66.1.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.66.1.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.66.1 did not satisfy the adapter ABI" >&2
  exit 1
}

for version in 0.61.0 0.65.0; do
  capabilities "$lib/flix-$version.jar" > "$work/$version.json"
  jq -e '.hasEngineApi and (.missing | length == 0)' "$work/$version.json" >/dev/null || {
    echo "test-compiler-compatibility: Flix $version did not satisfy the adapter ABI" >&2
    exit 1
  }
done

capabilities "$lib/flix-0.67.1.jar" > "$work/0.67.1.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.67.1.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.67.1 did not satisfy the adapter ABI" >&2
  exit 1
}

capabilities "$lib/flix-0.67.2.jar" > "$work/0.67.2.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.67.2.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.67.2 did not satisfy the adapter ABI" >&2
  exit 1
}

capabilities "$lib/flix-0.68.0.jar" > "$work/0.68.0.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.68.0.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.68.0 did not satisfy the adapter ABI" >&2
  exit 1
}

capabilities "$lib/flix-0.75.2.jar" > "$work/0.75.2.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.75.2.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.75.2 did not satisfy the adapter ABI" >&2
  exit 1
}

capabilities "$lib/flix-0.75.3.jar" > "$work/0.75.3.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.75.3.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.75.3 did not satisfy the adapter ABI" >&2
  exit 1
}

for version in 0.76.0 0.76.1 0.77.0 0.78.0 0.79.0; do
  capabilities "$lib/flix-$version.jar" > "$work/$version.json"
  jq -e '.hasEngineApi and (.missing | length == 0)' "$work/$version.json" >/dev/null || {
    echo "test-compiler-compatibility: Flix $version did not satisfy the adapter ABI" >&2
    exit 1
  }
done

mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" --no-server plugin.test.compile >/dev/null)
classpath=$(cd "$root" && "$mill" --no-server show plugin.test.runClasspath 2>/dev/null \
  | jq -r '.[] | split(":")[-1]' | paste -sd: -)
integration() {
  compiler=$1
  effect_surface=${2:-1}
  fixture=${3:-$root/plugin/test/fixtures/semantic}
  "$java_home/bin/java" -cp "$classpath:$compiler" \
    dev.flixw.metrics.PluginIntegrationTest \
    "$fixture" "$root/dist/plugin.jar" "$compiler" "$effect_surface"
}

integration "$lib/flix-0.60.0.jar" 0 "$root/plugin/test/fixtures/semantic-validation"
integration "$lib/flix-0.61.0.jar" 0 "$root/plugin/test/fixtures/semantic-validation"
integration "$lib/flix-0.65.0.jar" 0 "$root/plugin/test/fixtures/semantic-validation"
integration "$lib/flix-0.66.1.jar" 2
integration "$lib/flix-0.67.1.jar" 2
integration "$lib/flix-0.67.2.jar" 2
integration "$lib/flix-0.68.0.jar"
integration "$lib/flix-0.75.2.jar"
integration "$lib/flix-0.75.3.jar"
integration "$lib/flix-0.76.0.jar"
integration "$lib/flix-0.76.1.jar"
integration "$lib/flix-0.77.0.jar"
integration "$lib/flix-0.78.0.jar"
integration "$lib/flix-0.79.0.jar"

# Flix 0.79.0 starts a documented definition at its def line instead of its doc comment. The
# adapter makes up the difference, so one project must measure identically on every release:
# the same source, packaged plugin, compared apart from the provenance of each compiler.
span_report() {
  version=$1
  shift
  compiler=$lib/flix-$version.jar
  dir=$work/span-$version
  rm -rf "$dir" "$work/span-cache-$version"
  mkdir -p "$dir" "$work/span-cache-$version"
  # Written per compiler: Flix 0.78.0 and later reject manifest keys that earlier releases need.
  "$java_home/bin/java" -cp "$classpath:$compiler" dev.flixw.metrics.DefinitionSpanTest \
    write "$dir" >&2
  "$java_home/bin/java" -cp "$classpath:$compiler" dev.flixw.metrics.DefinitionSpanTest >&2
  FLIXW_ABI_VERSION=1 \
  FLIXW_PROJECT_ROOT=$dir \
  FLIXW_COMPILER_JAR=$compiler \
  FLIXW_JAVA_HOME=$java_home \
  FLIXW_PLUGIN_CACHE=$work/span-cache-$version \
    "$java_home/bin/java" -jar "$root/dist/plugin.jar" report --format json "$@"
}

for version in 0.76.1 0.77.0 0.78.0 0.79.0; do
  span_report "$version" > "$work/span-$version.json"
  jq -S 'del(.provenance)' "$work/span-$version.json" > "$work/span-$version.norm.json"
done
for version in 0.76.1 0.78.0 0.79.0; do
  cmp -s "$work/span-0.77.0.norm.json" "$work/span-$version.norm.json" || {
    echo "test-compiler-compatibility: Flix $version measured the definition-span project differently from 0.77.0" >&2
    diff "$work/span-0.77.0.norm.json" "$work/span-$version.norm.json" | head -20 >&2
    exit 1
  }
done
jq -e '[.smells[] | select(.rule == "definition-too-long")] | length == 12' \
  "$work/span-0.79.0.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.79.0 lost definition-too-long findings" >&2
  exit 1
}

# A baseline taken on 0.77.0 must hold on 0.79.0: nothing new, nothing resolved.
span_gate=$(span_report 0.79.0 --baseline "$work/span-0.77.0.json" --fail-on-new warning)
printf '%s\n' "$span_gate" | jq -e '
  .baseline.newCount == 0 and .baseline.resolvedCount == 0 and .baseline.worsenedCount == 0
' >/dev/null || {
  echo "test-compiler-compatibility: a 0.77.0 baseline does not hold on 0.79.0" >&2
  exit 1
}

echo "test-compiler-compatibility: 0.59.0 rejected at its AST and 0.66.0 at its Java runtime; 0.60.0 through 0.79.0 passed recorded integration checkpoints"
