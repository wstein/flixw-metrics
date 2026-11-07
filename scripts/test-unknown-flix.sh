#!/bin/sh
# Pin what the plugin does with a Flix release that has no compatibility checkpoint (for example
# 0.80.0), a decision of the repository owner.
#
# Admission is the bytecode-derived linkage gate in CompilerCapabilities (Adapters.resolve in the
# bridge JVM repeats it per adapter), never a version string. On top of it:
#   - a compiler that links and is in the verified list (dev/flixw/metrics/verified-flix-versions.txt):
#     no notice;
#   - a compiler that links and is not in the list: loaded as before, same report, exit 0, and exactly
#     one `metrics: warning: ...` line on stderr, also on a cache hit; nothing added to the report;
#   - a compiler that does not link: exit 2 and the sentence naming the missing members, no warning.
#
# No Flix 0.80.0 exists, so the unrecorded case is the verified 0.79.0 jar copied to a file named
# flix-0.80.0.jar, and the refused case is a stub jar that declares Implementation-Version 0.80.0 but
# lacks the compiler classes. The version comes from the jar file name (flixw's cache name is
# flix-<version>-<sha256>.jar).
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sample=$root/plugin/test/fixtures/release-sample
registry=$root/scripts/test-compiler-compatibility.sh
work=$root/out/compiler-compatibility
known=0.79.0

for command in curl java javac jar jq; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "test-unknown-flix: $command is required" >&2
    exit 2
  }
done
[ -f "$root/dist/plugin.jar" ] || {
  echo "test-unknown-flix: dist/plugin.jar is missing; run scripts/package.sh first" >&2
  exit 2
}
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi

java_home=${JAVA_HOME:-}
if [ -z "$java_home" ]; then
  java_home=$(dirname "$(dirname "$(command -v java)")")
fi
[ -x "$java_home/bin/java" ] || {
  echo "test-unknown-flix: no java executable under $java_home" >&2
  exit 2
}

scratch=$(mktemp -d "${TMPDIR:-/tmp}/flixw-metrics-unknown.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$work"

expected=$(awk -v v="$known" '$1 == "fetch" && $2 == v { print $3 }' "$registry")
[ "${#expected}" -eq 64 ] || {
  echo "test-unknown-flix: no 64-hex digest for Flix $known in test-compiler-compatibility.sh" >&2
  exit 1
}
jar=$work/flix-$known.jar
if [ -f "$jar" ]; then
  # shellcheck disable=SC2086  # $sum is a command plus flags, deliberately split
  [ "$($sum "$jar" | cut -d' ' -f1)" = "$expected" ] || rm -f "$jar"
fi
if [ ! -f "$jar" ]; then
  echo "test-unknown-flix: downloading Flix $known" >&2
  curl -fsSL -o "$jar.part" "https://github.com/flix/flix/releases/download/v$known/flix.jar"
  # shellcheck disable=SC2086
  if [ "$($sum "$jar.part" | cut -d' ' -f1)" != "$expected" ]; then
    rm -f "$jar.part"
    echo "test-unknown-flix: digest mismatch for Flix $known" >&2
    exit 1
  fi
  mv "$jar.part" "$jar"
fi

fail() {
  echo "test-unknown-flix: $1" >&2
  exit 1
}

# Runs the packaged plugin; sets status, stdout and stderr files under $scratch/$1.
plugin() {
  name=$1
  compiler=$2
  shift 2
  project=$scratch/$name/project
  mkdir -p "$project"
  cp -R "$sample/." "$project/"
  status=0
  FLIXW_ABI_VERSION=1 \
  FLIXW_PROJECT_ROOT=$project \
  FLIXW_COMPILER_JAR=$compiler \
  FLIXW_JAVA_HOME=$java_home \
  FLIXW_PLUGIN_CACHE=${cache:-} \
    "$java_home/bin/java" -jar "$root/dist/plugin.jar" "$@" \
    > "$scratch/$name/stdout.txt" 2> "$scratch/$name/stderr.txt" || status=$?
}

# 1. A verified release (the same jar under its own name): no notice at all.
mkdir -p "$scratch/verified"
cp "$jar" "$scratch/verified/flix-$known-$expected.jar"
plugin verified-report "$scratch/verified/flix-$known-$expected.jar" report --format json \
  --output "$scratch/verified-report/report.json"
[ "$status" -eq 0 ] || fail "report failed for the verified $known jar (exit $status)"
if grep -qi 'warning' "$scratch/verified-report/stderr.txt"; then
  fail "a verified release produced a warning"
fi

# 2. An unrecorded release that links: loaded, complete report, exactly one warning on stderr, also
# when the measurement comes from the cache.
mkdir -p "$scratch/newer"
cp "$jar" "$scratch/newer/flix-0.80.0.jar"
plugin newer-capabilities "$scratch/newer/flix-0.80.0.jar" capabilities
[ "$status" -eq 0 ] || fail "capabilities failed for the renamed 0.79.0 jar"
jq -e '.hasEngineApi and (.missing | length == 0)' "$scratch/newer-capabilities/stdout.txt" >/dev/null \
  || fail "a linking compiler named 0.80.0 was not accepted by the linkage gate"
cache=$scratch/cache
mkdir -p "$cache"
for run in newer-report newer-cached; do
  plugin "$run" "$scratch/newer/flix-0.80.0.jar" report --format json --diagnostics \
    --output "$scratch/$run/report.json"
  [ "$status" -eq 0 ] || fail "report failed for the renamed 0.79.0 jar ($run, exit $status)"
  jq -e '.summary.definitions == 7' "$scratch/$run/report.json" >/dev/null \
    || fail "the renamed 0.79.0 jar did not produce the sample report ($run)"
  [ "$(grep -c '^metrics: warning: ' "$scratch/$run/stderr.txt")" -eq 1 ] \
    || fail "an unrecorded release must produce exactly one warning ($run)"
  grep -q '^metrics: warning: Flix 0\.80\.0 ' "$scratch/$run/stderr.txt" \
    || fail "the warning does not name the release ($run)"
  grep -q 'test-compiler-compatibility.sh' "$scratch/$run/stderr.txt" \
    || fail "the warning does not point to the compatibility checkpoint ($run)"
  if grep -q 'metrics: warning' "$scratch/$run/report.json"; then
    fail "the warning leaked into the report ($run)"
  fi
done
grep -q '^metrics diagnostics: cache=miss ' "$scratch/newer-report/stderr.txt" \
  || fail "the first run for the unrecorded release was not a cache miss"
grep -q '^metrics diagnostics: cache=hit ' "$scratch/newer-cached/stderr.txt" \
  || fail "the second run for the unrecorded release did not come from the cache"
jq -e --slurpfile verified "$scratch/verified-report/report.json" \
  '.summary == $verified[0].summary' "$scratch/newer-report/report.json" >/dev/null \
  || fail "the report for an unrecorded release differs from the verified one"

# 3. An unrecorded release that does not link: exit 2 and a sentence about the missing members.
stub=$scratch/stub
mkdir -p "$stub/src/ca/uwaterloo/flix/api" "$stub/classes"
cat > "$stub/src/ca/uwaterloo/flix/api/Flix.java" <<'JAVA'
package ca.uwaterloo.flix.api;
public final class Flix { }
JAVA
javac -d "$stub/classes" "$stub/src/ca/uwaterloo/flix/api/Flix.java"
printf 'Implementation-Version: 0.80.0\n' > "$stub/manifest.txt"
jar --create --file "$stub/stub.jar" --manifest "$stub/manifest.txt" -C "$stub/classes" .
plugin stub-capabilities "$stub/stub.jar" capabilities
jq -e '(.hasFlixApi == true) and (.hasEngineApi == false) and (.missing | length > 0)' \
  "$scratch/stub-capabilities/stdout.txt" >/dev/null \
  || fail "the stub compiler was not reported as lacking the engine API"
plugin stub-report "$stub/stub.jar" report --format json
[ "$status" -eq 2 ] || fail "an incompatible compiler must exit 2, got $status"
grep -q '^metrics: the pinned compiler has no supported engine API$' "$scratch/stub-report/stderr.txt" \
  || fail "the refusal sentence changed"
grep -q '^       missing: .' "$scratch/stub-report/stderr.txt" \
  || fail "the refusal no longer names the missing members"
if grep -qi 'warning' "$scratch/stub-report/stderr.txt"; then
  fail "a refused run must not print the unrecorded-release warning"
fi
if grep -qE '0\.80|0\.79' "$scratch/stub-report/stderr.txt"; then
  fail "the refusal mentions a version, but admission is by linkage"
fi
[ ! -s "$scratch/stub-report/stdout.txt" ] || fail "a refused run must not write a report"

echo "test-unknown-flix: a verified release is silent; an unrecorded one that links warns once and loads; a non-linking one exits 2"
