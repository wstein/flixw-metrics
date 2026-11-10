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

printf '%s\n' '{"baseline":{"newCount":0,"worsenedCount":0,"resolvedCount":0,"measurementDeltas":[]},"smells":[]}' \
  > "$work/changes.json"
sh "$root/scripts/check-changes-view.sh" "$work/changes.json"
printf '%s\n' '{"baseline":{"newCount":1,"worsenedCount":0,"resolvedCount":0,"measurementDeltas":[]},"smells":[{}]}' \
  > "$work/changes.json"
if sh "$root/scripts/check-changes-view.sh" "$work/changes.json" \
    > /dev/null 2> "$work/changes-error"; then
  echo "test-calibration: non-empty changes view unexpectedly passed" >&2
  exit 1
fi
grep -q 'expected an empty compact changes report' "$work/changes-error"

# The Flix 0.78+ corpus variant rewrites each checked-out project before measuring it: [package]
# keeps only version, repository and flix, a file may be renamed to match its module, and a module
# named like its companion becomes `pub mod`.
variant=$root/calibration/corpus-0.78.json
[ -f "$variant" ] || { echo "test-calibration: no $variant" >&2; exit 1; }
jq -e '
  (.projects | length > 0) and
  all(.projects[]; (.rewrite.renames // []) | all(
      (.from | test("^src/[A-Za-z0-9_./-]+[.]flix$")) and (.to | test("^src/[A-Za-z0-9_./-]+[.]flix$")) and
      ((.from + .to) | contains("..") | not))) and
  all(.projects[]; (.rewrite.publicModules | type == "array")) and
  all(.projects[].rewrite.publicModules[]; (.file | test("^src/[A-Za-z0-9_./-]+[.]flix$")) and
      (.file | contains("..") | not) and (.module | test("^[A-Z][A-Za-z0-9_]*$")))
' "$variant" >/dev/null || {
  echo "test-calibration: every variant project needs rewrite.publicModules" >&2
  exit 1
}
for key in id repository commit; do
  [ "$(jq -S -c "[.projects[].$key]" "$variant")" = "$(jq -S -c "[.projects[].$key]" "$root/calibration/corpus.json")" ] || {
    echo "test-calibration: variant $key list differs from corpus.json" >&2
    exit 1
  }
done

# The variant's expectations are the 0.77.0 ones, apart from the longer Prelude of the newer
# compiler; a changed count must be a decision, not an edit that only the corpus run notices.
jq -e --slurpfile base "$root/calibration/corpus.json" '
  (.expected | del(.prelude.lines)) == ($base[0].expected | del(.prelude.lines)) and
  .expected.prelude.lines == 232 and .compilerSource.rewriteFixture == true
' "$variant" >/dev/null || {
  echo "test-calibration: variant expectations differ from corpus.json beyond prelude.lines" >&2
  exit 1
}

# A local checkout may be linked into place; the rewrite must change a copy, never the original.
mkdir -p "$work/real/src"
printf '%s\n' '[package]' 'name = "old"' 'version = "1.0.0"' > "$work/real/flix.toml"
printf '%s\n' 'mod Alpha {' '}' > "$work/real/src/Alpha.flix"
ln -s "$work/real" "$work/linked"
sh "$root/scripts/copy-corpus-source.sh" "$work/linked" "$work/linked-copy"
[ ! -L "$work/linked-copy" ] || { echo "test-calibration: the copy is a symlink" >&2; exit 1; }
printf '%s\n' '{"publicModules":[{"file":"src/Alpha.flix","module":"Alpha"}]}' > "$work/spec-linked.json"
sh "$root/scripts/rewrite-corpus-copy.sh" "$work/linked-copy" "$work/spec-linked.json" x
grep -q '^pub mod Alpha {$' "$work/linked-copy/src/Alpha.flix"
if grep -q '^name' "$work/linked-copy/flix.toml"; then
  echo "test-calibration: the rewrite kept the package name" >&2
  exit 1
fi
grep -q '^name = "old"$' "$work/real/flix.toml" || {
  echo "test-calibration: the rewrite changed the linked original" >&2
  exit 1
}
grep -q '^mod Alpha {$' "$work/real/src/Alpha.flix" || {
  echo "test-calibration: the rewrite changed the linked original source" >&2
  exit 1
}

mkdir -p "$work/p/src" "$work/p/test"
cat > "$work/p/flix.toml" <<'EOF'
[package]
name        = "old"
description = "old description"
version     = "1.2.3"
flix        = "0.73.0"
license     = "MIT"
authors     = ["A B <a@example.com>"]
repository  = "github:a/old"

[mvn-dependencies]
"org.example:lib" = "1.0"
EOF
printf '%s\n' 'mod Alpha {' '    pub def f(): Int32 = 1' '}' 'mod AlphaNot {' '}' > "$work/p/src/Main.flix"
printf '%s\n' '{"renames":[{"from":"src/Main.flix","to":"src/Alpha.flix"}],"publicModules":[{"file":"src/Alpha.flix","module":"Alpha"}]}' > "$work/spec.json"
sh "$root/scripts/rewrite-corpus-copy.sh" "$work/p" "$work/spec.json" "github:a/old@0123"
package_keys=$(awk '/^\[/ { in_package = ($0 == "[package]"); next }
  in_package && /^[A-Za-z_-]+[ ]*=/ { sub(/[ ]*=.*/, ""); print }' "$work/p/flix.toml" | sort | tr '\n' ' ')
[ "$package_keys" = "flix repository version " ] || {
  echo "test-calibration: rewritten [package] keys are: $package_keys" >&2
  exit 1
}
grep -q '^version     = "1.2.3"$' "$work/p/flix.toml"
grep -q '^"org.example:lib" = "1.0"$' "$work/p/flix.toml"
grep -q '^# corpus-0.78 rewrite of github:a/old@0123: removed name, description, license, authors$' "$work/p/flix.toml"
grep -q '^pub mod Alpha {$' "$work/p/src/Alpha.flix"
grep -q '^mod AlphaNot {$' "$work/p/src/Alpha.flix"
[ ! -e "$work/p/src/Main.flix" ]

# A rewrite that matches nothing means the pin moved or the spec is stale; it must not pass silently.
printf '%s\n' '{"publicModules":[{"file":"src/Alpha.flix","module":"Missing"}]}' > "$work/spec-missing.json"
if sh "$root/scripts/rewrite-corpus-copy.sh" "$work/p" "$work/spec-missing.json" x \
    > /dev/null 2> "$work/rewrite-error"; then
  echo "test-calibration: rewrite of a missing module unexpectedly passed" >&2
  exit 1
fi
grep -q 'no unique "mod Missing {" line' "$work/rewrite-error"
printf '%s\n' '{"publicModules":[{"file":"../x.flix","module":"Alpha"}]}' > "$work/spec-escape.json"
if sh "$root/scripts/rewrite-corpus-copy.sh" "$work/p" "$work/spec-escape.json" x \
    > /dev/null 2> "$work/rewrite-error"; then
  echo "test-calibration: rewrite outside src unexpectedly passed" >&2
  exit 1
fi
grep -q 'unsafe path' "$work/rewrite-error"
printf '%s\n' '{"publicModules":[{"file":"src/../x.flix","module":"Alpha"}]}' > "$work/spec-escape.json"
if sh "$root/scripts/rewrite-corpus-copy.sh" "$work/p" "$work/spec-escape.json" x \
    > /dev/null 2> "$work/rewrite-error"; then
  echo "test-calibration: a path with .. inside src unexpectedly passed" >&2
  exit 1
fi
grep -q 'unsafe path src/../x.flix' "$work/rewrite-error"
printf '%s\n' '{"renames":[{"from":"src/Nope.flix","to":"src/Beta.flix"}],"publicModules":[]}' > "$work/spec-rename.json"
if sh "$root/scripts/rewrite-corpus-copy.sh" "$work/p" "$work/spec-rename.json" x \
    > /dev/null 2> "$work/rewrite-error"; then
  echo "test-calibration: rename of a missing file unexpectedly passed" >&2
  exit 1
fi
grep -q 'no source src/Nope.flix' "$work/rewrite-error"
echo "test-calibration: ok"
