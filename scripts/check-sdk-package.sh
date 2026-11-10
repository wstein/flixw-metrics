#!/bin/sh
# Inspect the single plugin artifact: kit classes and its license must be embedded.
set -eu
plugin=${1:?usage: sh scripts/check-sdk-package.sh plugin.jar}
for class in Context Usage Launcher InspectionLoader AtomicOutput KeyedStore ProvenanceData ExitCodes; do
  unzip -Z1 "$plugin" | grep -qx "dev/flixw/sdk/kit/$class.class" || {
    echo "sdk-package: missing embedded kit class $class" >&2; exit 1;
  }
done
unzip -p "$plugin" META-INF/LICENSE-flixw-plugin-sdk | grep -q 'Apache License' || {
  echo 'sdk-package: missing SDK license' >&2; exit 1;
}
printf '%s\n' 'sdk-package: 9 passed, 0 failed'
