#!/bin/sh
# Compile everything with warnings fatal. -Xfatal-warnings on the Scala side is the point of
# this project's build: a match over Flix's AST that forgets a construct is a metric that is
# quietly wrong, so it fails here rather than under-reporting in someone's terminal.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sh "$root/scripts/fetch-flix.sh"
mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" --no-server plugin.compile flix0753.compile flix0680.compile flix0672.compile \
  flix0661.compile flix0610.compile flix0600.compile)
