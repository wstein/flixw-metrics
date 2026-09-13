#!/bin/sh
# An identical-input changes view must be compact and completely empty.
set -eu

report=${1:?usage: sh scripts/check-changes-view.sh <changes-report.json>}
command -v jq >/dev/null 2>&1 || {
  echo "check-changes-view: jq is required" >&2
  exit 2
}

jq -e '
  (.baseline.newCount == 0) and
  (.baseline.worsenedCount == 0) and
  (.baseline.resolvedCount == 0) and
  ((.baseline.measurementDeltas | length) == 0) and
  ((.smells | length) == 0) and
  (has("definitions") | not) and
  (has("modules") | not) and
  (has("rankings") | not)
' "$report" >/dev/null || {
  echo "check-changes-view: expected an empty compact changes report: $report" >&2
  exit 2
}
