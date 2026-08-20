#!/bin/sh
# Build plugin.jar with mill.
#
# The jar holds this module's classes and nothing else. scala-library is deliberately not
# bundled: the compiler jar already carries 2.13, and the bridge phase puts both on one flat
# class path, so a second copy would let ordering decide which one the engine links against.
set -eu

version=${1:?usage: sh scripts/package.sh <version>}
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
dist=$root/dist

sh "$root/scripts/fetch-flix.sh"

mkdir -p "$dist"
# ./mill over a system mill: the wrapper downloads the version pinned in .mill-version, so a
# release built on a CI runner and one built here are the same build. A system mill is
# whatever that machine happens to have.
mill=$root/mill
[ -x "$mill" ] || mill=mill
(cd "$root" && "$mill" plugin.jar >/dev/null)

# `mill show` prints `<content-hash>:<path>`; the path is everything from the first slash.
built=$(cd "$root" && "$mill" show plugin.jar 2>/dev/null | tr -d '"' | sed 's|^[^/]*||')
[ -f "$built" ] || { echo "package: mill produced no jar at $built" >&2; exit 1; }

# The version is stamped here rather than in build.mill so a release is one argument, not an
# edit; mill's own jar carries no Implementation-Version of its own.
#
# Added to mill's manifest rather than replacing it. This used to write a fresh one naming
# only Main-Class, which silently dropped everything build.mill declares -- including
# Flixw-Plugin-Description, the attribute flixw reads to say what this plugin is for. The
# released jar had it in the build and not in the artifact.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
(cd "$work" && unzip -qo "$built")
# Only the main section: a blank line ends it, and an attribute appended past one would land
# in a per-entry section, where nothing looks for it.
tr -d '\r' < "$work/META-INF/MANIFEST.MF" | awk 'NF==0{exit} {print}' > "$work/manifest.txt"
printf 'Implementation-Version: %s\n' "$version" >> "$work/manifest.txt"
rm -rf "$work/META-INF"
(cd "$work" && jar --create --file "$dist/plugin.jar" --manifest manifest.txt .)

# What build.mill declares must survive packaging, or the release is a jar that does not say
# what it is. Checked on the built artifact, because that is the thing that ships.
for attr in Main-Class Flixw-Plugin-Description Flixw-Plugin-Command Implementation-Version; do
  unzip -p "$dist/plugin.jar" META-INF/MANIFEST.MF | tr -d '\r' | grep -q "^$attr:" || {
    echo "package: $attr missing from the packaged manifest" >&2; exit 1; }
done

(cd "$dist" && shasum -a 256 plugin.jar > SHA256SUMS)
printf 'built %s/plugin.jar (%s)\n' "$dist" "$version"
