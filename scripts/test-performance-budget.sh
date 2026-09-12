#!/bin/sh
# Offline boundary tests for performance-budget enforcement.
set -eu

# shellcheck disable=SC1007  # Empty CDPATH is scoped to cd, not assigned to root.
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

printf '%s\n' '{"schemaVersion":1,"samples":3,"coldMedianMaxMs":100,"warmMedianMaxMs":20,"warmToColdMaxPercent":20}' > "$work/budget.json"
printf '%s\n' '{"schemaVersion":1,"coldMs":[90,100,110],"warmMs":[10,20,25],"coldMedianMs":100,"warmMedianMs":20,"warmToColdPercent":20}' > "$work/pass.json"
sh "$root/scripts/check-performance-budget.sh" "$work/pass.json" "$work/budget.json"

printf '%s\n' '{"schemaVersion":1,"coldMs":[90,101,110],"warmMs":[10,21,25],"coldMedianMs":101,"warmMedianMs":21,"warmToColdPercent":21}' > "$work/fail.json"
if sh "$root/scripts/check-performance-budget.sh" "$work/fail.json" "$work/budget.json" \
    2> "$work/error"; then
  echo "test-performance-budget: over-budget measurements unexpectedly passed" >&2
  exit 1
fi
grep -q 'cold median 101ms exceeds 100ms' "$work/error"
grep -q 'warm median 21ms exceeds 20ms' "$work/error"
grep -q 'warm/cold ratio 21% exceeds 20%' "$work/error"
echo "test-performance-budget: ok"
