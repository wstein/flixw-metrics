# Contributing

Thank you for improving flixw-metrics. Bug reports, metric proposals, documentation fixes, and
code contributions are welcome.

## Before writing code

Search the [open issues](https://github.com/wstein/flixw-metrics/issues) first. Use the bug form
for incorrect behavior and the feature form for a new measurement or policy idea. Security
vulnerabilities must follow the private process in [SECURITY.md](SECURITY.md), not a public issue.

Metric proposals should define what is counted, why the typed compiler representation is the
right source of truth, how the result will be calibrated, and whether the SDK, wire format,
cache, JSON schema, or report formats would change.

## Development setup

The build requires Java 21, `jq`, `unzip`, and `shasum`. The checked-in `./mill` launcher
bootstraps the build tool, and `scripts/fetch-flix.sh` downloads digest-verified compiler jars.
On its first run Mill downloads Temurin 21 itself (`mill-jvm-version` in `build.mill`; the default would be `zulu:25`).
Generated `out/`, `dist/`, and `plugin/lib/flix*.jar` files must not be committed.

The plugin embeds `kit` from the exact source revision in `sdk-source.properties`.
Clone the repository named there, check out its full `revision`, and set
`FLIXW_SDK_SOURCE` to that checkout's absolute root before invoking Mill or the
source checks. The checkout must have that exact HEAD and no tracked or untracked
changes; ignored build outputs outside `kit/src` and `kit/resources` are allowed.
Consumed files must match their pinned Git blobs even when index flags hide edits.
A missing path, wrong revision, or
changed source fails before plugin compilation. There is no download or sibling
checkout fallback. CI checks out the same revision explicitly.

Only `kit/src` and `kit/resources` enter the plugin artifact. The kit classes and
`META-INF/LICENSE-flixw-plugin-sdk` are embedded in `plugin.jar`; no SDK JAR or
plugin dependency is required at runtime. The source pin is an interim development
contract; publishing and consuming a versioned release asset is a separate step.

Useful commands:

```console
make format
make lint
sh scripts/test-one.sh MetricsTest
./mill --no-server plugin.test.compile
make test
./mill plugin.docJar
```

`make test` is the required pre-PR check. It compiles every adapter with fatal warnings, exercises
the real compiler fixtures and historical compatibility boundaries, verifies calibration and
performance contracts, and proves package reproducibility.

## Architecture constraints

Compiler-neutral Java code lives under `plugin/src/dev/flixw/metrics/`. Flix compiler types may
appear only in the Scala adapter source of the SDK's `flix-bridge`, one family per `adapter.flixNNNN`
module. Values crossing `dev.flixw.sdk.bridge.CompilerModel` must remain compiler-neutral strings, numbers, booleans, paths, and
collections.

Adapters are selected by their bytecode-derived linkage requirements, not version strings. A new
compiler family therefore requires a change in `wstein/flixw-plugin-sdk` (an isolated adapter module,
a family and release entries in `versions.json`, conformance tests and a compatibility record) and a
new `sdk-source.properties` pin here, with the packaged integration checkpoint. Do not replace
the derived ABI contract with a handwritten capability list.

Changes to measurement behavior or default thresholds require corpus calibration evidence. Keep
measurement caching separate from findings and formatting so policy changes take effect on cache
hits.

## Commits and pull requests

Make focused, atomic commits with Conventional Commit subjects such as `fix:`, `feat:`, `test:`,
`docs:`, `refactor:`, `perf:`, or `ci:`. Each commit should build independently.

A pull request should:

- explain the behavior change and motivation;
- cite calibration evidence for metric or threshold changes;
- list the verification commands run;
- state compatibility impacts for the report schema, wire/cache format, SDK, capability JSON,
  CLI, and packaged adapter classes; and
- link the relevant issue and, for a new compiler release, the SDK pull request with its compatibility
  record.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
