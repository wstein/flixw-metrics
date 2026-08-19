# flix-metrics

An experimental [flixw](https://github.com/wstein/flixw) plugin for compiler-driven Flix
metrics. It runs as a Java 21 JAR and consumes flixw ABI version 1.

The plugin is deliberately capability-gated. It opens the exact compiler JAR flixw pinned,
in an isolated class loader, and refuses to claim semantic metrics unless the expected
compiler-side model is present. It never silently replaces compiler metrics with a text scan.

The initial backend forwards to the compiler's native `metric` command when its metric model
is present. The ongoing reflective backend will derive the report from the checked compiler
model directly; it is version-gated because Flix internals are not a public plugin API.

## Build

```sh
sh scripts/package.sh 0.1.0
```

This writes `dist/plugin.jar` and `dist/SHA256SUMS`. Publish both as immutable release
assets, then install the JAR explicitly from a tagged release:

```sh
./flixw plugin install flix-metrics 0.1.0 \
  https://github.com/wstein/flix-metrics/releases/download/v0.1.0/plugin.jar \
  --sha256 "$(awk '/plugin.jar$/ { print $1 }' dist/SHA256SUMS)"
```

Then run `./flixw plugin flix-metrics report --format json`, or inspect the compiler-side
capabilities with `./flixw plugin flix-metrics capabilities`.

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
