#!/bin/sh
# Offline contract test for scripts/copy-corpus-source.sh: its guards and its refusal of links that
# leave the copy.
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

echo "test-corpus-source: ok"
