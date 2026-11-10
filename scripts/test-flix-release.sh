#!/bin/sh
# Offline contract test for scripts/check-flix-release.sh, the weekly watcher for a Flix release
# without a compatibility checkpoint. A stub `curl` stands in for GitHub: it prints the URL the
# redirect would end at, or fails the way `curl -f` does.
set -eu

# shellcheck disable=SC1007  # Empty CDPATH is scoped to cd, not assigned to root.
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
mkdir "$work/bin"
cat > "$work/bin/curl" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" > "$STUB_ARGS"
[ "${STUB_EXIT:-0}" -eq 0 ] || exit "$STUB_EXIT"
printf '%s' "${STUB_URL-}"
STUB
chmod 755 "$work/bin/curl"

fail() { echo "test-flix-release: $1" >&2; exit 1; }

# run <expected-exit> <stub-url> <stub-curl-exit> <label> [<expected stderr text>]
run() {
  expected=$1
  status=0
  PATH="$work/bin:$PATH" STUB_ARGS="$work/args" STUB_URL=$2 STUB_EXIT=$3 \
    FLIX_RELEASE_URL=https://example.invalid/releases/latest FLIXW_VERSIONS_JSON=${FLIXW_VERSIONS_JSON:-} \
    sh "$root/scripts/check-flix-release.sh" > "$work/out" 2> "$work/err" || status=$?
  [ "$status" -eq "$expected" ] || fail "$4: exit $status, expected $expected ($(cat "$work/err"))"
  [ -z "${5-}" ] || grep -Fq -- "$5" "$work/err" || fail "$4: stderr lacks '$5' ($(cat "$work/err"))"
}

newest=$(sh "$root/scripts/sdk-versions.sh" verified | tail -n 1)
[ -n "$newest" ] || fail "no verified release in the pinned versions.json"
# A document with the given releases, as versions.json lists them.
document() {
  status=$1
  shift
  jq -n --arg status "$status" '$ARGS.positional | map({version: ., sha256: ("0" * 64), status: $status,
    family: "flix0761", fixture: "semantic"}) | {schema: 1, families: [], releases: .}' --args "$@" \
    > "$work/versions.json"
}

run 0 "https://github.com/flix/flix/releases/tag/v$newest" 0 "the newest verified release"
grep -q "^newest Flix release: $newest\$" "$work/out" || fail "the release is not announced"
grep -q 'https://example.invalid/releases/latest$' "$work/args" || fail "the URL is not overridable"
# -f makes an HTTP error a curl failure instead of a redirect target that happens to look fine.
case " $(cat "$work/args") " in
  *" -fsSLI "*) ;;
  *) fail "curl is not run with -fsSLI" ;;
esac

run 1 "https://github.com/flix/flix/releases/tag/v0.999.0" 0 "an unlisted release"
grep -q 'versions.json' "$work/err" || fail "the message does not name the version data"
grep -q '0.999.0' "$work/err" || fail "the message does not name the release"
# 0.59.0 and 0.66.0 are listed in versions.json, but as rejected releases.
run 1 "https://github.com/flix/flix/releases/tag/v0.59.0" 0 "a fetched but rejected release (0.59.0)"
run 1 "https://github.com/flix/flix/releases/tag/v0.66.0" 0 "a fetched but rejected release (0.66.0)"
run 1 "https://github.com/flix/flix/releases/tag/v0.7.0" 0 "an unlisted release near listed ones" \
  "is not in the verified releases of versions.json"
run 1 "https://github.com/flix/flix/releases/tag/v$newest-rc1" 0 "a prerelease" "could not resolve a Flix version"
run 1 "https://github.com/flix/flix/releases/tag/v0x79.0" 0 "a malformed version" "could not resolve a Flix version"
run 1 "https://github.com/flix/flix/releases/tag/v" 0 "an empty version" "could not resolve a Flix version"
run 1 "https://github.com/flix/flix/releases" 0 "a URL without a tag" "did not end at a release tag"
run 1 "" 0 "an empty URL" "did not end at a release tag"
# The list is matched by whole lines: 0.79.0 is not verified because 10.79.0 is.
document verified 10.79.0
FLIXW_VERSIONS_JSON=$work/versions.json run 1 "https://github.com/flix/flix/releases/tag/v0.79.0" 0 "a release that is a substring of a listed one" \
  "is not in"
FLIXW_VERSIONS_JSON=$work/versions.json run 0 "https://github.com/flix/flix/releases/tag/v10.79.0" 0 "a release in an alternative list"
# The dots of the version are literal.
document verified 0.7x9.0
FLIXW_VERSIONS_JSON=$work/versions.json run 1 "https://github.com/flix/flix/releases/tag/v0.79.0" 0 "a list line that only matches as a pattern" \
  "is not in"
# A release the data lists as rejected has no checkpoint.
document rejected 0.80.0
FLIXW_VERSIONS_JSON=$work/versions.json run 1 "https://github.com/flix/flix/releases/tag/v0.80.0" 0 "a rejected release" \
  "is not in the verified releases"
# A value with a line break must not pass line by line.
multi=$(printf 'https://github.com/flix/flix/releases/tag/v0.999.0\n%s' "$newest")
run 1 "$multi" 0 "a version with a line break" "could not resolve a Flix version"
# The call is bounded and retried.
case " $(cat "$work/args") " in
  *" --max-time "*" --retry "*) ;;
  *) fail "curl is run without --max-time and --retry" ;;
esac
# curl -f ends 22 on an HTTP error; the usable-looking URL must not matter.
run 1 "https://github.com/flix/flix/releases/tag/v$newest" 22 "a failing curl" "could not read the newest"

echo "test-flix-release: ok"
