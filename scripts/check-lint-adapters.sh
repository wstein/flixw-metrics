#!/bin/sh
# scripts/lint.sh must compile every adapter module declared in build.mill; a module missing from
# its task list would be built without -Xfatal-warnings coverage in CI's lint step.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
status=0
for name in $(sed -n 's/^  object \(flix[0-9]*\) extends \(ScalaModule\|FlixAdapter\).*/\1/p' "$root/build.mill"); do
  if ! grep -q "adapter\.$name\.compile" "$root/scripts/lint.sh"; then
    echo "check-lint-adapters: scripts/lint.sh does not compile adapter.$name" >&2
    status=1
  fi
done
exit $status
