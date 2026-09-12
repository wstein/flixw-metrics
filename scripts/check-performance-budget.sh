#!/bin/sh
# Check a repeated timing summary against explicit absolute and relative budgets.
set -eu

measurements=${1:?usage: sh scripts/check-performance-budget.sh <measurements.json> <budget.json>}
budget=${2:?usage: sh scripts/check-performance-budget.sh <measurements.json> <budget.json>}

command -v jq >/dev/null 2>&1 || {
  echo "check-performance-budget: jq is required" >&2
  exit 2
}
for file in "$measurements" "$budget"; do
  [ -f "$file" ] || { echo "check-performance-budget: no file $file" >&2; exit 2; }
done

jq -e '
  .schemaVersion == 1 and
  (.coldMs | type == "array" and length > 0) and
  (.warmMs | type == "array" and length > 0) and
  (.coldMedianMs | type == "number") and
  (.warmMedianMs | type == "number") and
  (.warmToColdPercent | type == "number")
' "$measurements" >/dev/null || {
  echo "check-performance-budget: invalid measurements" >&2
  exit 2
}
jq -e '
  .schemaVersion == 1 and (.samples >= 3) and (.samples % 2 == 1) and
  (.coldMedianMaxMs > 0) and (.warmMedianMaxMs > 0) and
  (.warmToColdMaxPercent > 0)
' "$budget" >/dev/null || {
  echo "check-performance-budget: invalid budget" >&2
  exit 2
}

expected=$(jq -r '.samples' "$budget")
cold_samples=$(jq -r '.coldMs | length' "$measurements")
warm_samples=$(jq -r '.warmMs | length' "$measurements")
[ "$cold_samples" -eq "$expected" ] && [ "$warm_samples" -eq "$expected" ] || {
  echo "check-performance-budget: expected $expected cold and warm samples" >&2
  exit 2
}

failed=0
check() {
  label=$1
  actual=$2
  maximum=$3
  unit=$4
  if [ "$actual" -gt "$maximum" ]; then
    echo "check-performance-budget: $label $actual$unit exceeds $maximum$unit" >&2
    failed=1
  fi
}
check "cold median" "$(jq -r '.coldMedianMs' "$measurements")" \
  "$(jq -r '.coldMedianMaxMs' "$budget")" "ms"
check "warm median" "$(jq -r '.warmMedianMs' "$measurements")" \
  "$(jq -r '.warmMedianMaxMs' "$budget")" "ms"
check "warm/cold ratio" "$(jq -r '.warmToColdPercent' "$measurements")" \
  "$(jq -r '.warmToColdMaxPercent' "$budget")" "%"
[ "$failed" -eq 0 ]
