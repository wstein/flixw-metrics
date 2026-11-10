#!/bin/sh
# Fail when the newest Flix release is not in the verified list. The adapter binds to compiler
# internals, and a Flix release changes them without a commit here; the weekly flix-release workflow
# runs this so a new release is noticed before a user's project stops measuring.
#
# The checkpoint is the release's line in plugin/resources/dev/flixw/metrics/verified-flix-versions.txt
# (the list that switches the plugin's unknown-release warning off); test-compiler-compatibility.sh
# checks that every listed release also has a `fetch` line and a packaged-integration checkpoint.
# This only checks that a checkpoint exists. Whether the release passes is decided by
# `scripts/test-compiler-compatibility.sh` once the checkpoint is added.
#
# FLIX_RELEASE_URL overrides the URL that redirects to the newest release tag (for tests).
set -eu

# shellcheck disable=SC1007  # Empty CDPATH is scoped to cd, not assigned to root.
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
list=plugin/resources/dev/flixw/metrics/verified-flix-versions.txt
url=${FLIX_RELEASE_URL:-https://github.com/flix/flix/releases/latest}

# The tag, not the API's JSON body: one redirect, and no rate limit. No pipe: sh has no pipefail,
# and a failed curl behind a pipe would end in the exit status of the last command.
effective=$(curl -fsSLI --max-time 60 --retry 3 -o /dev/null -w '%{url_effective}' "$url") || {
  echo "check-flix-release: could not read the newest Flix release from $url" >&2
  exit 1
}
case $effective in
  */tag/v*) v=${effective##*/tag/v} ;;
  *)
    echo "check-flix-release: $url did not end at a release tag (got '$effective')" >&2
    exit 1
    ;;
esac
printf '%s\n' "$v" | grep -Eq '^[0-9]+[.][0-9]+[.][0-9]+$' || {
  echo "check-flix-release: could not resolve a Flix version (got '$v')" >&2
  exit 1
}
echo "newest Flix release: $v"
if ! grep -Fxq "$v" "$root/$list"; then
  echo "::error::Flix $v is not in $list;" \
       "add its checkpoint to scripts/test-compiler-compatibility.sh, the version to that list," \
       "and record docs/compiler-compatibility/flix-$v.md" >&2
  exit 1
fi
