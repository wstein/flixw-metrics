#!/bin/sh
# Offline contract test for the local calibration source: scripts/copy-corpus-source.sh (its guards
# and its refusal of links that leave the copy) and its use by scripts/calibrate-corpus.sh, where a
# rewritten project must be measured from a copy and never from a link to the original checkout.
set -eu

# shellcheck disable=SC1007  # Empty CDPATH is scoped to cd, not assigned to root.
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

fail() { echo "test-corpus-source: $1" >&2; exit 1; }

# expect_exit <status> <stderr text> <label> -- <command...>
expect_exit() {
  want=$1; text=$2; label=$3
  shift 4
  status=0
  "$@" > /dev/null 2> "$work/err" || status=$?
  [ "$status" -eq "$want" ] || fail "$label: exit $status, expected $want ($(cat "$work/err"))"
  grep -Fq -- "$text" "$work/err" || fail "$label: stderr lacks '$text' ($(cat "$work/err"))"
}

copy=$root/scripts/copy-corpus-source.sh

# The guards.
expect_exit 2 'no checkout' "a missing checkout" -- sh "$copy" "$work/missing" "$work/t1"
[ ! -e "$work/t1" ] || fail "a missing checkout still created the target"
mkdir -p "$work/real/src" "$work/taken"
printf '%s\n' 'keep' > "$work/taken/marker"
expect_exit 2 'already exists' "an existing target" -- sh "$copy" "$work/real" "$work/taken"
[ "$(cat "$work/taken/marker")" = keep ] || fail "the existing target was changed"
[ ! -e "$work/taken/src" ] || fail "the existing target received files"

# A link that stays inside the tree is copied as a link; the copy is a different tree.
printf '%s\n' 'mod Alpha {' '}' > "$work/real/src/Alpha.flix"
ln -s Alpha.flix "$work/real/src/Beta.flix"
mkdir -p "$work/real/src/sub"
ln -s ../Alpha.flix "$work/real/src/sub/Up.flix"
sh "$copy" "$work/real" "$work/inside"
[ -L "$work/inside/src/Beta.flix" ] || fail "an inner link was not kept as a link"
[ -L "$work/inside/src/sub/Up.flix" ] || fail "an inner link that climbs one level was refused or flattened"
printf '%s\n' changed > "$work/inside/src/Alpha.flix"
grep -q '^mod Alpha {$' "$work/real/src/Alpha.flix" || fail "the copy shares files with the original"

# A link that leaves the tree would let a rewrite of the copy change another directory.
mkdir -p "$work/elsewhere"
printf '%s\n' 'mod Gamma {' '}' > "$work/elsewhere/Gamma.flix"
ln -s "$work/elsewhere" "$work/abs-src"
mkdir -p "$work/abs"
ln -s "$work/abs-src" "$work/abs/src"
expect_exit 2 'leaves the checkout' "an absolute link out of the tree" -- sh "$copy" "$work/abs" "$work/t2"
[ ! -e "$work/t2" ] || fail "a refused copy was left behind"
mkdir -p "$work/rel/src"
ln -s ../../elsewhere "$work/rel/src/up"
expect_exit 2 'leaves the checkout' "a relative link out of the tree" -- sh "$copy" "$work/rel" "$work/t3"
[ ! -e "$work/t3" ] || fail "a refused copy was left behind"
mkdir -p "$work/dangling"
ln -s /no/such/place "$work/dangling/gone"
expect_exit 2 'leaves the checkout' "an absolute dangling link" -- sh "$copy" "$work/dangling" "$work/t4"

# calibrate-corpus.sh in a scratch tree of its own scripts, with stubs for everything that needs a
# compiler: the java stub records how each project was presented and writes the report files.
tree=$work/tree
mkdir -p "$tree/scripts" "$tree/calibration" "$tree/dist" "$work/jh/bin"
for script in calibrate-corpus copy-corpus-source rewrite-corpus-copy check-changes-view; do
  cp "$root/scripts/$script.sh" "$tree/scripts/"
done
for stub in package calibrate-prelude check-calibration; do
  printf '%s\n' '#!/bin/sh' 'exit 0' > "$tree/scripts/$stub.sh"
done
cat > "$work/jh/bin/java" <<'STUB'
#!/bin/sh
out=
while [ $# -gt 0 ]; do
  [ "$1" != --output ] || out=$2
  shift
done
if [ -L "$FLIXW_PROJECT_ROOT" ]; then kind=link; else kind=directory; fi
if grep -q '^name' "$FLIXW_PROJECT_ROOT/flix.toml"; then named=name; else named=no-name; fi
printf '%s %s %s\n' "$(basename "$FLIXW_PROJECT_ROOT")" "$kind" "$named" >> "$STUB_LOG"
printf '%s\n' '{"baseline":{"newCount":0,"worsenedCount":0,"resolvedCount":0,"measurementDeltas":[]},"smells":[]}' > "$out"
STUB
chmod 755 "$work/jh/bin/java"
: > "$work/jar"
: > "$tree/dist/plugin.jar"

sources=$work/sources
commit_of() { git -C "$1" rev-parse HEAD; }
for id in flix rewritten plain; do
  mkdir -p "$sources/$id/src"
  git -C "$sources/$id" init -q
  printf '%s\n' '[package]' 'name = "old"' 'version = "1.0.0"' 'flix = "0.79.0"' > "$sources/$id/flix.toml"
  printf '%s\n' 'mod Alpha {' '}' > "$sources/$id/src/Alpha.flix"
  git -C "$sources/$id" add -A
  GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.invalid GIT_COMMITTER_NAME=t \
    GIT_COMMITTER_EMAIL=t@example.invalid git -C "$sources/$id" commit -q -m pin
done
# The project directories are links to the checkouts, as an owner may keep them.
mkdir -p "$work/checkouts"
for id in flix rewritten plain; do ln -s "$sources/$id" "$work/checkouts/$id"; done
jq -n --arg f "$(commit_of "$sources/flix")" --arg r "$(commit_of "$sources/rewritten")" \
    --arg p "$(commit_of "$sources/plain")" '{
  compilerSource: {repository: "https://example.invalid/flix", commit: $f, prelude: "flix.toml"},
  projects: [
    {id: "rewritten", repository: "https://example.invalid/r", commit: $r,
     rewrite: {publicModules: [{file: "src/Alpha.flix", module: "Alpha"}]}},
    {id: "plain", repository: "https://example.invalid/p", commit: $p}],
  incompatibleProjects: [], datalogExamples: []}' > "$tree/calibration/corpus.json"

STUB_LOG=$work/log
export STUB_LOG
: > "$STUB_LOG"
CALIBRATION_SOURCE_ROOT=$work/checkouts CALIBRATION_JAVA_HOME=$work/jh \
  CALIBRATION_COMPILER_JAR=$work/jar PATH="$work/jh/bin:$PATH" \
  sh "$tree/scripts/calibrate-corpus.sh" "$work/output" > /dev/null 2> "$work/cal-err" \
  || fail "calibrate-corpus.sh failed offline ($(cat "$work/cal-err"))"
grep -qx 'rewritten directory no-name' "$STUB_LOG" \
  || fail "a rewritten project was not measured from a rewritten copy ($(tr '\n' ';' < "$STUB_LOG"))"
grep -qx 'plain link name' "$STUB_LOG" \
  || fail "a project without a rewrite is not linked to its checkout ($(tr '\n' ';' < "$STUB_LOG"))"
grep -q '^name = "old"$' "$sources/rewritten/flix.toml" || fail "the original checkout was rewritten"
grep -q '^mod Alpha {$' "$sources/rewritten/src/Alpha.flix" || fail "the original source was rewritten"
[ -z "$(git -C "$sources/rewritten" status --porcelain)" ] || fail "the original checkout is dirty"

echo "test-corpus-source: ok"
