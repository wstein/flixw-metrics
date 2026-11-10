#!/bin/sh
# Exercise the pinned source boundary without starting a JVM or downloading sources.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
mkdir -p "$work/sdk/kit/src" "$work/sdk/kit/resources"
git -C "$work/sdk" init -q
printf 'out/\n' > "$work/sdk/.gitignore"
printf 'source\n' > "$work/sdk/kit/src/Example.java"
git -C "$work/sdk" add .
git -C "$work/sdk" commit -qm 'test: create pinned SDK source fixture'
revision=$(git -C "$work/sdk" rev-parse HEAD)
pin=$work/sdk-source.properties
write_pin() {
  printf 'repository=https://github.com/wstein/flixw-plugin-sdk\nrevision=%s\nmodule=kit\n' "$1" > "$pin"
}
write_pin "$revision"
checks=0
accept() {
  actual=$(FLIXW_SDK_SOURCE="$work/sdk" sh "$root/scripts/check-sdk-source.sh" "$pin")
  [ "$actual" = "$work/sdk" ] || { echo 'test-sdk-source: wrong source path' >&2; exit 1; }
  checks=$((checks + 1))
}
reject() {
  if FLIXW_SDK_SOURCE="$work/sdk" sh "$root/scripts/check-sdk-source.sh" "$pin" > "$work/out" 2> "$work/error"; then
    echo "test-sdk-source: accepted $1" >&2; exit 1
  fi
  grep -q "$2" "$work/error" || { cat "$work/error" >&2; exit 1; }
  checks=$((checks + 1))
}
accept
mkdir -p "$work/sdk/out"
printf 'ignored build output\n' > "$work/sdk/out/classes"
accept
write_pin 0000000000000000000000000000000000000000
reject 'wrong revision' 'HEAD'
write_pin abc123
reject 'short revision' '40'
write_pin "$revision"
printf 'edited\n' >> "$work/sdk/kit/src/Example.java"
reject 'tracked source edit' 'changed'
git -C "$work/sdk" add kit/src/Example.java
reject 'staged source edit' 'changed'
git -C "$work/sdk" restore --staged kit/src/Example.java
git -C "$work/sdk" restore kit/src/Example.java
printf 'untracked\n' > "$work/sdk/kit/src/Unexpected.java"
reject 'untracked source' 'changed'
rm "$work/sdk/kit/src/Unexpected.java"
if env -u FLIXW_SDK_SOURCE sh "$root/scripts/check-sdk-source.sh" "$pin" > "$work/out" 2> "$work/error"; then
  echo 'test-sdk-source: accepted missing source path' >&2; exit 1
fi
grep -q FLIXW_SDK_SOURCE "$work/error"
checks=$((checks + 1))
if FLIXW_SDK_SOURCE="$work/absent" sh "$root/scripts/check-sdk-source.sh" "$pin" > "$work/out" 2> "$work/error"; then
  echo 'test-sdk-source: accepted absent directory' >&2; exit 1
fi
grep -q FLIXW_SDK_SOURCE "$work/error"
checks=$((checks + 1))
# A subtree must not borrow its enclosing checkout's HEAD and dirty status.
if FLIXW_SDK_SOURCE="$work/sdk/kit" sh "$root/scripts/check-sdk-source.sh" "$pin" > "$work/out" 2> "$work/error"; then
  echo 'test-sdk-source: accepted checkout subtree' >&2; exit 1
fi
grep -q 'root' "$work/error"
checks=$((checks + 1))
printf 'repository=https://example.invalid/sdk\nrevision=%s\nmodule=kit\n' "$revision" > "$pin"
reject 'unsupported repository' 'repository'
printf 'repository=https://github.com/wstein/flixw-plugin-sdk\nrevision=%s\nmodule=flix-bridge\n' "$revision" > "$pin"
reject 'unsupported module' 'module'
printf 'test-sdk-source: %s passed, 0 failed\n' "$checks"
