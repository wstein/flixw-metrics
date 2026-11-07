#!/bin/sh
# Mill runs javac in its own JVM, not in the JDK that is on PATH. Without a pinned
# `mill-jvm-version` Mill 1.3 downloads its default (zulu:25), so the build would be linted by a
# newer javac than the Java 21 this project targets. Fail unless Mill itself runs on Java 21.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
mill=$root/mill
[ -x "$mill" ] || mill=mill
version=$(cd "$root" && "$mill" --no-server --version)
case "$version" in
  *'java.version: 21.'*) ;;
  *)
    echo 'check-mill-jdk: Mill does not run on Java 21; pin mill-jvm-version in build.mill' >&2
    printf '%s\n' "$version" | grep 'java.version' >&2 || true
    exit 1
    ;;
esac
