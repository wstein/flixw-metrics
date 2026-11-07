#!/bin/sh
# Clone every calibration input at its full SHA, measure it, and reject metric drift.
set -eu

output=${1:?usage: sh scripts/calibrate-corpus.sh <empty-output-directory>}
# shellcheck disable=SC1007  # Empty CDPATH is scoped to cd, not assigned to root.
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
manifest=${CALIBRATION_MANIFEST:-$root/calibration/corpus.json}
compiler_jar=${CALIBRATION_COMPILER_JAR:-$root/plugin/lib/flix.jar}
[ -f "$manifest" ] || {
  echo "calibrate-corpus: no manifest $manifest" >&2
  exit 2
}
[ -f "$compiler_jar" ] || {
  echo "calibrate-corpus: no compiler jar $compiler_jar" >&2
  exit 2
}

for command in git jq java; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "calibrate-corpus: $command is required" >&2
    exit 2
  }
done

# IDs become file names and commits are fetched directly. Validate both before either is used.
jq -e '
  ([.projects[].id, .incompatibleProjects[].id,
      (.datalogExamples[] | "datalog-" + .), "prelude"]
    | all(test("^[a-z0-9][a-z0-9-]*$"))) and
  ([.compilerSource.commit, .projects[].commit, .incompatibleProjects[].commit]
    | all(test("^[0-9a-f]{40}$")))
' "$manifest" >/dev/null || {
  echo "calibrate-corpus: manifest contains an unsafe id or non-full commit" >&2
  exit 2
}

mkdir -p "$output"
if [ -n "$(find "$output" -mindepth 1 -print -quit)" ]; then
  echo "calibrate-corpus: output directory is not empty: $output" >&2
  exit 2
fi
output=$(CDPATH= cd -- "$output" && pwd -P)
mkdir -p "$output/reports" "$output/changes"
cp "$manifest" "$output/corpus.json"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
source_root=${CALIBRATION_SOURCE_ROOT:-}
java_home=${CALIBRATION_JAVA_HOME:-${JAVA_HOME:-}}
if [ -z "$java_home" ]; then
  java_home=$(dirname "$(dirname "$(command -v java)")")
fi
[ -x "$java_home/bin/java" ] || {
  echo "calibrate-corpus: no java executable under $java_home" >&2
  exit 2
}

clone_pin() {
  repository=$1
  commit=$2
  target=$3
  source=${4:-}
  rewritten=${5:-}
  if [ -n "$source" ]; then
    [ -d "$source/.git" ] || {
      echo "calibrate-corpus: no local checkout $source" >&2
      exit 2
    }
    if [ -n "$rewritten" ]; then
      # A rewrite edits files, so it works on a copy and never touches the local checkout,
      # which may itself be a symlink.
      sh "$root/scripts/copy-corpus-source.sh" "$source" "$target"
    else
      ln -s "$source" "$target"
    fi
  else
    mkdir -p "$target"
    git -C "$target" init -q
    git -C "$target" remote add origin "$repository"
    git -C "$target" fetch --depth=1 origin "$commit"
    git -C "$target" checkout -q --detach FETCH_HEAD
  fi
  actual=$(git -C "$target" rev-parse HEAD)
  [ "$actual" = "$commit" ] || {
    echo "calibrate-corpus: $repository resolved to $actual, expected $commit" >&2
    exit 2
  }
}

run_project() {
  id=$1
  project=$2
  cache=$work/cache/$id
  report=$output/reports/$id.json
  changes=$output/changes/$id.json
  mkdir -p "$cache"
  echo "calibrate-corpus: measuring $id" >&2
  FLIXW_ABI_VERSION=1 \
  FLIXW_PROJECT_ROOT=$project \
  FLIXW_COMPILER_JAR=$compiler_jar \
  FLIXW_JAVA_HOME=$java_home \
  FLIXW_PLUGIN_CACHE=$cache \
    "$java_home/bin/java" -jar "$root/dist/plugin.jar" report --format json \
      --output "$report"
  # Exercise both the warm-cache path and the compact comparison view. Identical pinned inputs
  # must not manufacture finding or raw-measurement changes.
  FLIXW_ABI_VERSION=1 \
  FLIXW_PROJECT_ROOT=$project \
  FLIXW_COMPILER_JAR=$compiler_jar \
  FLIXW_JAVA_HOME=$java_home \
  FLIXW_PLUGIN_CACHE=$cache \
    "$java_home/bin/java" -jar "$root/dist/plugin.jar" report --format json \
      --view changes --baseline "$report" --output "$changes"
  sh "$root/scripts/check-changes-view.sh" "$changes"
}

sh "$root/scripts/package.sh" 0.0.0-calibration >/dev/null

compiler_repository=$(jq -r '.compilerSource.repository' "$manifest")
compiler_commit=$(jq -r '.compilerSource.commit' "$manifest")
compiler_checkout=$work/flix
compiler_source=${source_root:+$source_root/flix}
clone_pin "$compiler_repository" "$compiler_commit" "$compiler_checkout" "$compiler_source"

prelude=$(jq -r '.compilerSource.prelude' "$manifest")
[ -f "$compiler_checkout/$prelude" ] || {
  echo "calibrate-corpus: missing pinned Prelude source $prelude" >&2
  exit 2
}
rewrite_fixture=0
if [ "$(jq -r '.compilerSource.rewriteFixture // false' "$manifest")" = true ]; then
  rewrite_fixture=1
fi
echo "calibrate-corpus: measuring prelude" >&2
CALIBRATION_REWRITE_FIXTURE=$rewrite_fixture \
CALIBRATION_COMPILER_JAR=$compiler_jar \
  sh "$root/scripts/calibrate-prelude.sh" "$compiler_checkout" \
  > "$output/reports/prelude.json"

for id in $(jq -r '.projects[].id' "$manifest"); do
  repository=$(jq -r --arg id "$id" '.projects[] | select(.id == $id) | .repository' "$manifest")
  commit=$(jq -r --arg id "$id" '.projects[] | select(.id == $id) | .commit' "$manifest")
  checkout=$work/$id
  local_source=${source_root:+$source_root/$id}
  rewrite=$(jq -c --arg id "$id" '.projects[] | select(.id == $id) | .rewrite // empty' "$manifest")
  clone_pin "$repository" "$commit" "$checkout" "$local_source" "$rewrite"
  if [ -n "$rewrite" ]; then
    printf '%s\n' "$rewrite" > "$work/$id.rewrite.json"
    sh "$root/scripts/rewrite-corpus-copy.sh" "$checkout" "$work/$id.rewrite.json" \
      "$repository@$commit"
  fi
  run_project "$id" "$checkout"
done

for example in $(jq -r '.datalogExamples[]' "$manifest"); do
  id=datalog-$example
  project=$work/$id
  source=$compiler_checkout/examples/datalog/$example.flix
  [ -f "$source" ] || {
    echo "calibrate-corpus: missing pinned Datalog source $source" >&2
    exit 2
  }
  mkdir -p "$project/src"
  cp "$source" "$project/src/$example.flix"
  run_project "$id" "$project"
done

sh "$root/scripts/check-calibration.sh" "$manifest" "$output/reports" \
  > "$output/summary.json"
echo "calibrate-corpus: all pinned expectations match" >&2
