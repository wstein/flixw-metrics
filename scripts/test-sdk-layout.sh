#!/bin/sh
# Run the actual build with a fresh SDK source path inside the repository, as in CI.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
source=$(sh "$root/scripts/check-sdk-source.sh")
work=$(mktemp -d "$root/.sdk-layout-test-XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM
# A unique path forces source tasks to evaluate even when the previous compile was cached.
git clone -q --shared --no-checkout "$source" "$work/sdk"
revision=$(git -C "$source" rev-parse HEAD)
git -C "$work/sdk" checkout -q --detach "$revision"
mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && FLIXW_SDK_SOURCE="$work/sdk" "$mill" --no-server plugin.compile + plugin.resources + adapter.flix0600.compile)
printf '%s\n' 'sdk-layout: 3 passed, 0 failed'
