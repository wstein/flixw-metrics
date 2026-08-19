# flixw-metrics

An experimental [flixw](https://github.com/wstein/flixw) plugin for compiler-driven Flix
metrics. It runs as a Java 21 JAR and consumes flixw ABI version 1.

Built with [mill](https://mill-build.org): `sh scripts/package.sh <version>`. The build fetches
the pinned Flix release into `plugin/lib` and checks its digest, because the engine is compiled
against one compiler's AST and "it built here" should mean something.

The plugin is deliberately capability-gated. It opens the exact compiler JAR flixw pinned,
in an isolated class loader, and refuses to claim semantic metrics unless the expected
compiler-side model is present. It never silently replaces compiler metrics with a text scan.

The reflective backend derives the report from the compiler's own typed root. It is gated on
the exact members it reflects against, because Flix internals are not a public plugin API and
a version string is not evidence — see [docs/COMPILER-SDK.md](docs/COMPILER-SDK.md).

Reports are cached under `FLIXW_CACHE_HOME` and reused until the sources, `flix.toml`, the
pinned compiler or this plugin change. That is worth roughly 28x on a warm run (~4.5s to
~0.16s), because the compiler spends most of a cold run type-checking its own standard
library before it reaches your code. Every cache failure is a miss, never a stale answer.

## Build

```sh
sh scripts/package.sh 0.1.0
```

This writes `dist/plugin.jar` and `dist/SHA256SUMS`. Publish both as immutable release
assets, then install the JAR explicitly from a tagged release:

```sh
./flixw plugin install flixw-metrics 0.1.0 \
  https://github.com/wstein/flixw-metrics/releases/download/v0.1.0/plugin.jar \
  --sha256 "$(awk '/plugin.jar$/ { print $1 }' dist/SHA256SUMS)"
```

Then run `./flixw plugin flixw-metrics report --format json`, or inspect the compiler-side
capabilities with `./flixw plugin flixw-metrics capabilities`.

It reports counts of definitions, modules, local definitions, effectful signatures and
branches from the compiler's typed root, line metrics from the source text, and the smells
those imply — see [docs/COMPILER-SDK.md](docs/COMPILER-SDK.md) for which number comes from where
and why.

## ABI and safety

Plugins receive their command-line arguments normally and run with the selected project JVM.
This plugin requires `FLIXW_PROJECT_ROOT`, `FLIXW_COMPILER_JAR`, `FLIXW_JAVA_HOME`, and
`FLIXW_ABI_VERSION=1`. It uses the compiler JAR flixw already verified; it does not download
or choose another compiler.

The native backend works with compiler builds that contain
`ca.uwaterloo.flix.tools.Metrics` and expose `metric`. Stock Flix builds that lack this
model currently produce a clear unsupported-capability diagnostic. Supporting them requires
a verified reflective adapter for their exact compiler API, not a source-level approximation.

A SHA-256 proves that an installed artifact has the expected bytes. It does not make the
plugin safe: plugins are unsandboxed code running as their caller. Publish immutable tag
URLs, ask users to supply `--sha256`, and do not place secrets or whole environment dumps in
output.

See flixw's [plugin contract](https://github.com/wstein/flixw/blob/main/docs/CONTRACT.md#flixw-plugin)
for the complete ABI.
