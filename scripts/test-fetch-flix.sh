#!/bin/sh
# Offline test of scripts/fetch-flix.sh with a throwaway data file and local file:// sources.
set -eu
umask 022
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/metrics-fetch-test.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi
digest() { printf '%s' "$1" | $sum | cut -d' ' -f1; }
for v in 1.0.0 1.1.0 2.0.0 3.0.0; do
  mkdir -p "$work/src/v$v"
  printf 'jar %s' "$v" > "$work/src/v$v/flix.jar"
done
# Two families; 1.0.0 and 1.1.0 are floors, 1.1.0 and 2.0.0 are test releases (2.0.0 of the newest
# family); 3.0.0 is a rejected release that only the full set fetches. 9.0.0 has a wrong digest.
cat > "$work/data.json" <<JSON
{"schema":1,
 "families":[{"id":"famB","adapter":"B","floor":"2.0.0","testVersion":"2.0.0","capabilities":["metrics"]},
             {"id":"famA","adapter":"A","floor":"1.0.0","testVersion":"1.1.0","capabilities":["metrics"]}],
 "releases":[{"version":"1.0.0","sha256":"$(digest 'jar 1.0.0')","status":"verified","family":"famA","fixture":"x"},
             {"version":"1.1.0","sha256":"$(digest 'jar 1.1.0')","status":"verified","family":"famA","fixture":"x"},
             {"version":"2.0.0","sha256":"$(digest 'jar 2.0.0')","status":"verified","family":"famB","fixture":"x"},
             {"version":"3.0.0","sha256":"$(digest 'jar 3.0.0')","status":"rejected","rejection":"x"},
             {"version":"9.0.0","sha256":"$(digest 'another jar')","status":"rejected","rejection":"x"}]}
JSON
mkdir -p "$work/src/v9.0.0"
printf 'jar 9.0.0' > "$work/src/v9.0.0/flix.jar"
run() {
  FLIXW_VERSIONS_JSON=$work/data.json FLIXW_PLUGIN_LIB=$work/lib FLIXW_FLIX_BASE_URL=file://$work/src \
    sh "$root/scripts/fetch-flix.sh" "$@" 2>"$work/err"
}
fail() { echo "test-fetch-flix: $1" >&2; cat "$work/err" >&2; exit 1; }
checks=0
ok() { checks=$((checks + 1)); }

# The default set: the floor and test release of every family, and flix.jar as the newest family's
# test release. Rejected releases stay out.
run || fail "the default set was refused"
for v in 1.0.0 1.1.0 2.0.0; do [ -f "$work/lib/flix-$v.jar" ] || fail "flix-$v.jar was not placed"; done
ok
[ ! -e "$work/lib/flix-3.0.0.jar" ] || fail "a rejected release was fetched by default"
ok
cmp -s "$work/lib/flix.jar" "$work/lib/flix-2.0.0.jar" || fail "flix.jar is not the newest family's test release"
ok

# Everything the data names, rejected releases included, except a digest mismatch aborts.
status=0; run all || status=$?
[ "$status" = 1 ] || fail "a digest mismatch in the full set did not exit 1 (got $status)"
grep -q 'digest mismatch for Flix 9.0.0' "$work/err" || fail "the mismatch was not named"
[ ! -e "$work/lib/flix-9.0.0.jar" ] && [ ! -e "$work/lib/flix-9.0.0.jar.part" ] \
  || fail "a mismatching jar was kept"
ok
[ -f "$work/lib/flix-3.0.0.jar" ] || fail "the full set did not fetch the rejected release 3.0.0"
ok

# A tampered jar is replaced, and so is a tampered flix.jar.
printf 'tampered' > "$work/lib/flix-1.0.0.jar"
printf 'tampered' > "$work/lib/flix.jar"
run || fail "a tampered jar was not replaced"
[ "$($sum "$work/lib/flix-1.0.0.jar" | cut -d' ' -f1)" = "$(digest 'jar 1.0.0')" ] || fail "the tampered jar stayed"
cmp -s "$work/lib/flix.jar" "$work/lib/flix-2.0.0.jar" || fail "the tampered flix.jar stayed"
ok

# Named versions: only those; an unlisted version is refused.
rm -rf "$work/lib"
run 1.0.0 || fail "a named version was refused"
[ -f "$work/lib/flix-1.0.0.jar" ] && [ ! -e "$work/lib/flix-1.1.0.jar" ] || fail "named versions fetched more"
ok
status=0; run 7.7.7 || status=$?
[ "$status" = 1 ] || fail "an unlisted version did not exit 1 (got $status)"
grep -q '7.7.7 is not in' "$work/err" || fail "the unlisted version was not named"
ok
printf 'test-fetch-flix: %s passed, 0 failed\n' "$checks"
