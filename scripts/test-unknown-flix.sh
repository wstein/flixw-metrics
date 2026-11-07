#!/bin/sh
# Pin what the plugin does with a Flix release newer than any recorded one (for example 0.80.0).
#
# The plugin never reads a version string. Admission is the bytecode-derived linkage gate in
# CompilerCapabilities (Adapters.resolve in the bridge JVM repeats it per adapter). So:
#   - a compiler that links is accepted silently, whatever its name or version;
#   - a compiler that does not link is refused with exit 2 and a sentence naming the missing members.
# Neither case mentions the version or warns that the release has no compatibility record.
#
# No Flix 0.80.0 exists, so the first case is the verified 0.79.0 jar copied to a file named
# flix-0.80.0.jar, and the second is a stub jar that declares Implementation-Version 0.80.0 but
# lacks the compiler classes. This changes no behavior; it records the current one.
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
    "$java_home/bin/java" -jar "$root/dist/plugin.jar" "$@" \
    > "$scratch/$name/stdout.txt" 2> "$scratch/$name/stderr.txt" || status=$?
}

# 1. An unrecorded release that links: accepted, no warning, a complete report.
mkdir -p "$scratch/newer"
cp "$jar" "$scratch/newer/flix-0.80.0.jar"
plugin newer-capabilities "$scratch/newer/flix-0.80.0.jar" capabilities
[ "$status" -eq 0 ] || fail "capabilities failed for the renamed 0.79.0 jar"
jq -e '.hasEngineApi and (.missing | length == 0)' "$scratch/newer-capabilities/stdout.txt" >/dev/null \
  || fail "a linking compiler named 0.80.0 was not accepted by the linkage gate"
plugin newer-report "$scratch/newer/flix-0.80.0.jar" report --format json \
  --output "$scratch/newer-report/report.json"
[ "$status" -eq 0 ] || fail "report failed for the renamed 0.79.0 jar (exit $status)"
jq -e '.summary.definitions == 7' "$scratch/newer-report/report.json" >/dev/null \
  || fail "the renamed 0.79.0 jar did not produce the sample report"
if grep -qiE 'warn|unknown|unsupported|newer|0\.80' \
    "$scratch/newer-report/stdout.txt" "$scratch/newer-report/stderr.txt"; then
  fail "an unrecorded release that links produced a version warning or notice"
fi

# 2. An unrecorded release that does not link: exit 2 and a sentence about the missing members.
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
if grep -qE '0\.80|0\.79' "$scratch/stub-report/stderr.txt"; then
  fail "the refusal mentions a version, but admission is by linkage"
fi
[ ! -s "$scratch/stub-report/stdout.txt" ] || fail "a refused run must not write a report"

echo "test-unknown-flix: a linking unrecorded release is loaded silently; a non-linking one exits 2 naming missing members"
