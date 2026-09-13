#!/bin/sh
# Produce a native report through the packaged plugin, then validate its published contract.
set -eu

# shellcheck disable=SC1007  # Empty CDPATH is scoped to cd, not assigned to root.
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
for command in java check-jsonschema; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "validate-report-schema: $command is required" >&2
    exit 2
  }
done

java_home=${JAVA_HOME:-}
if [ -z "$java_home" ]; then
  java_home=$(dirname "$(dirname "$(command -v java)")")
fi
[ -x "$java_home/bin/java" ] || {
  echo "validate-report-schema: no java executable under $java_home" >&2
  exit 2
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
cp -R "$root/plugin/test/fixtures/semantic" "$work/project"
mkdir -p "$work/cache"
sh "$root/scripts/package.sh" 0.0.0-schema >/dev/null

FLIXW_ABI_VERSION=1 \
FLIXW_PROJECT_ROOT=$work/project \
FLIXW_COMPILER_JAR=$root/plugin/lib/flix.jar \
FLIXW_JAVA_HOME=$java_home \
FLIXW_PLUGIN_CACHE=$work/cache \
  "$java_home/bin/java" -jar "$root/dist/plugin.jar" report --format json \
    --output "$work/report.json"

check-jsonschema --schemafile "$root/docs/metrics-report.schema.json" "$work/report.json"
