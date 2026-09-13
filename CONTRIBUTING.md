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
Generated `out/`, `dist/`, and `plugin/lib/flix*.jar` files must not be committed.

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
appear only in the Scala adapter source under a versioned `adapter.flixNNNN` module. Values crossing
`sdk.CompilerModel` must remain compiler-neutral strings, numbers, booleans, paths, and
collections.

Adapters are selected by their bytecode-derived linkage requirements, not version strings. A new
compiler family therefore requires an isolated adapter module, an `Adapters.KNOWN` entry, semantic
and packaged integration tests, and a record under `docs/compiler-compatibility/`. Do not replace
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
- link the relevant issue and compiler-compatibility investigation.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
