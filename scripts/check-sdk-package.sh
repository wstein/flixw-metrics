#!/bin/sh
# Inspect the single plugin artifact: the kit and flix-bridge classes, the seven adapter families,
# the version data and the SDK license must be embedded; nothing of the former in-repository copies
# and no compiler or Scala class may be.
set -eu
plugin=${1:?usage: sh scripts/check-sdk-package.sh plugin.jar}
entries=$(mktemp)
trap 'rm -f "$entries"' EXIT INT TERM
unzip -Z1 "$plugin" > "$entries"
fail() { echo "sdk-package: $1" >&2; exit 1; }
has() { grep -qx "$1" "$entries"; }
checks=0
for class in Context Usage Launcher InspectionLoader AtomicOutput KeyedStore ProvenanceData ExitCodes; do
  has "dev/flixw/sdk/kit/$class.class" || fail "missing embedded kit class $class"
  checks=$((checks + 1))
done
for class in AdapterAbi Adapters Capability CompilerCapabilities CompilerModel DatalogGraph Json VersionData; do
  has "dev/flixw/sdk/bridge/$class.class" || fail "missing embedded bridge class $class"
  checks=$((checks + 1))
done
for family in 0600 0610 0661 0672 0680 0753 0761; do
  has "dev/flixw/sdk/bridge/adapter/Flix${family}Adapter.class" || fail "missing the Flix$family adapter"
  checks=$((checks + 1))
done
has dev/flixw/sdk/bridge/versions.json || fail 'missing the version data'
checks=$((checks + 1))
unzip -p "$plugin" dev/flixw/sdk/bridge/versions.json | grep -q '"flix0761"' || fail 'the version data names no families'
checks=$((checks + 1))
unzip -p "$plugin" META-INF/LICENSE-flixw-plugin-sdk | grep -q 'Apache License' || fail 'missing SDK license'
checks=$((checks + 1))
# The former in-repository copies must be gone, not merely shadowed.
if grep -Eq '^dev/flixw/metrics/(sdk/|adapter/|DatalogGraph|CompilerCapabilities|CompilerVersions)|^dev/flixw/metrics/verified-flix-versions\.txt$' "$entries"; then
  fail 'the jar still contains an in-repository copy of an SDK class or of the version list'
fi
checks=$((checks + 1))
# One flat class path with the compiler: no compiler, Scala library or jar inside the plugin.
if grep -Eq '^(scala/|ca/uwaterloo/flix/)|\.jar$' "$entries"; then fail 'the jar contains a compiler, the Scala library or a jar'; fi
checks=$((checks + 1))
printf 'sdk-package: %s passed, 0 failed\n' "$checks"
