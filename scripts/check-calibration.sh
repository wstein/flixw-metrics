#!/bin/sh
# Reduce native reports to stable calibration facts and reject drift from the pinned corpus.
set -eu

manifest=${1:?usage: sh scripts/check-calibration.sh <corpus.json> <report-directory>}
reports=${2:?usage: sh scripts/check-calibration.sh <corpus.json> <report-directory>}

command -v jq >/dev/null 2>&1 || {
  echo "check-calibration: jq is required" >&2
  exit 2
}
[ -f "$manifest" ] || { echo "check-calibration: no manifest $manifest" >&2; exit 2; }
[ -d "$reports" ] || { echo "check-calibration: no report directory $reports" >&2; exit 2; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
failed=0

for id in $(jq -r '.expected | keys[]' "$manifest"); do
  report=$reports/$id.json
  if [ ! -f "$report" ]; then
    echo "check-calibration: missing report for $id" >&2
    failed=1
    continue
  fi
  if ! jq -e '.schemaVersion and .summary and (.smells | type == "array")' \
      "$report" >/dev/null; then
    echo "check-calibration: invalid native report for $id" >&2
    failed=1
    continue
  fi
  jq -S -c '{
    files: .summary.files,
    definitions: .summary.definitions,
    lines: .summary.lines,
    datalogRules: .summary.datalogRules,
    datalogFacts: .summary.datalogFacts,
    findings: (.smells | length),
    rules: (.smells | group_by(.rule) | map({key: .[0].rule, value: length}) | from_entries)
  }' "$report" > "$work/$id.actual"
  jq -S -c --arg id "$id" '.expected[$id]' "$manifest" > "$work/$id.expected"
  if ! cmp -s "$work/$id.expected" "$work/$id.actual"; then
    echo "check-calibration: drift in $id" >&2
    diff -u "$work/$id.expected" "$work/$id.actual" >&2 || true
    failed=1
  fi
  jq -c --arg id "$id" '{key: $id, value: .}' "$work/$id.actual" \
    > "$work/$id.entry"
done

[ "$failed" -eq 0 ] || exit 1
jq -s '{schemaVersion: 1, targets: from_entries}' "$work"/*.entry
