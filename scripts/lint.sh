#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
find "$root/src/main/java" -name '*.java' -print0 | xargs -0 javac -Xlint:all -Werror -d "$work"
