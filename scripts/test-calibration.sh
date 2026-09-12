#!/bin/sh
# Offline contract test for calibration reduction and drift detection.
set -eu

# shellcheck disable=SC1007  # Empty CDPATH is scoped to cd, not assigned to root.
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
mkdir -p "$work/reports"

printf '%s\n' '{"expected":{"alpha":{"files":1,"definitions":2,"lines":3,"datalogRules":4,"datalogFacts":5,"findings":1,"rules":{"dense":1}}}}' \
  > "$work/corpus.json"
printf '%s\n' '{"schemaVersion":16,"summary":{"files":1,"definitions":2,"lines":3,"datalogRules":4,"datalogFacts":5},"smells":[{"rule":"dense"}]}' \
  > "$work/reports/alpha.json"

sh "$root/scripts/check-calibration.sh" "$work/corpus.json" "$work/reports" \
  > "$work/summary.json"
jq -e '.targets.alpha.findings == 1 and .targets.alpha.rules.dense == 1' \
  "$work/summary.json" >/dev/null

printf '%s\n' '{"schemaVersion":16,"summary":{"files":1,"definitions":2,"lines":3,"datalogRules":4,"datalogFacts":5},"smells":[]}' \
  > "$work/reports/alpha.json"
if sh "$root/scripts/check-calibration.sh" "$work/corpus.json" "$work/reports" \
    > /dev/null 2> "$work/error"; then
  echo "test-calibration: drift unexpectedly passed" >&2
  exit 1
fi
grep -q 'drift in alpha' "$work/error"
echo "test-calibration: ok"
