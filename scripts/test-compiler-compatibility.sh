#!/bin/sh
# Lock the oldest runnable Flix release to 0.66.1, with its broken predecessor as proof.
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

fetch 0.66.0 e3910bb06f3c60e2439ba4cc9bbf0fc51ca67c6ad06eceb8e0e28804741b67a3
fetch 0.66.1 71b46d37d9c2e24b4eabd67ed2491a33adbb1a039e7bc49ef1f7556870e6344d
fetch 0.67.1 2f888a5c1ca2b343915add0ca697d36167fd16ae82e61b37bc0be29d09a1d8c4
fetch 0.67.2 3162ba033d77e481c8cd731441e1f279bd8f44e5f278b2b21632149c30f8ed3f
fetch 0.68.0 af568a2d4046207f908f8ec37786409b3dccdec944c2df5f571444599fb6b7c8
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

capabilities "$work/flix-0.66.0.jar" > "$work/0.66.0.json"
jq -e '
  (.hasEngineApi == false) and
  any(.missing[]; contains("dev.flix.runtime.Global"))
' "$work/0.66.0.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.66.0 did not fail its Java runtime boundary" >&2
  exit 1
}

capabilities "$work/flix-0.66.1.jar" > "$work/0.66.1.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.66.1.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.66.1 did not satisfy the adapter ABI" >&2
  exit 1
}

capabilities "$work/flix-0.67.1.jar" > "$work/0.67.1.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.67.1.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.67.1 did not satisfy the adapter ABI" >&2
  exit 1
}

capabilities "$work/flix-0.67.2.jar" > "$work/0.67.2.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.67.2.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.67.2 did not satisfy the adapter ABI" >&2
  exit 1
}

capabilities "$work/flix-0.68.0.jar" > "$work/0.68.0.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.68.0.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.68.0 did not satisfy the adapter ABI" >&2
  exit 1
}

capabilities "$work/flix-0.75.2.jar" > "$work/0.75.2.json"
jq -e '.hasEngineApi and (.missing | length == 0)' "$work/0.75.2.json" >/dev/null || {
  echo "test-compiler-compatibility: Flix 0.75.2 did not satisfy the adapter ABI" >&2
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
integration() {
  compiler=$1
  effect_surface=${2:-1}
  "$java_home/bin/java" -cp "$classpath:$compiler" \
    dev.flixw.metrics.PluginIntegrationTest \
    "$root/plugin/test/fixtures/semantic" "$root/dist/plugin.jar" "$compiler" "$effect_surface"
}

integration "$work/flix-0.66.1.jar" 2
integration "$work/flix-0.67.1.jar" 2
integration "$work/flix-0.67.2.jar" 2
integration "$work/flix-0.68.0.jar"
integration "$work/flix-0.75.2.jar"
integration "$work/flix-0.75.3.jar"

echo "test-compiler-compatibility: 0.66.0 rejected; 0.66.1, 0.67.1, 0.67.2, 0.68.0, 0.75.2, and 0.75.3 integration passed"
