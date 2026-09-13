#!/bin/sh
# Lock the oldest supported Flix ABI to 0.75.3, with the immediately preceding release as proof.
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

if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi

fetch() {
  version=$1
  expected=$2
  jar=$work/flix-$version.jar
  if [ -f "$jar" ]; then
    # shellcheck disable=SC2086  # $sum is a command plus flags, deliberately split
    actual=$($sum "$jar" | cut -d' ' -f1)
    [ "$actual" = "$expected" ] && return
    rm -f "$jar"
  fi
  url=https://github.com/flix/flix/releases/download/v$version/flix.jar
  echo "test-compiler-compatibility: downloading Flix $version" >&2
  curl -fsSL -o "$jar.part" "$url"
  # shellcheck disable=SC2086
  actual=$($sum "$jar.part" | cut -d' ' -f1)
  if [ "$actual" != "$expected" ]; then
    rm -f "$jar.part"
    echo "test-compiler-compatibility: digest mismatch for Flix $version" >&2
    exit 1
  fi
  mv "$jar.part" "$jar"
}

fetch 0.75.2 a2697d875725a0dde6e793b8d54cb220e86167a6d49ec5f0ccb0832966c8c15a
fetch 0.75.3 bf123cdb6494d6e0cbff6399bf185314d332bbe97bfd776e4abc03a5d39dd954

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

capabilities "$work/flix-0.75.2.jar" > "$work/0.75.2.json"
jq -e '
  (.hasEngineApi == false) and
  any(.missing[]; contains("TypedAst$Spec.fparams"))
' "$work/0.75.2.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.75.2 did not fail the expected ABI boundary" >&2
  exit 1
}

capabilities "$work/flix-0.75.3.jar" > "$work/0.75.3.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.75.3.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.75.3 did not satisfy the adapter ABI" >&2
  exit 1
}

mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" --no-server plugin.test.compile >/dev/null)
classpath=$(cd "$root" && "$mill" --no-server show plugin.test.runClasspath 2>/dev/null \
  | jq -r '.[] | split(":")[-1]' | paste -sd: -)
"$java_home/bin/java" -cp "$classpath:$work/flix-0.75.3.jar" \
  dev.flixw.metrics.PluginIntegrationTest \
  "$root/plugin/test/fixtures/semantic" "$root/dist/plugin.jar" "$work/flix-0.75.3.jar"

echo "test-compiler-compatibility: 0.75.2 rejected; 0.75.3 integration passed"
