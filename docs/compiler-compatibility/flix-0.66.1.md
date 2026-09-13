# Flix 0.66.1 compatibility investigation

## Inputs

- Release: `v0.66.1`
- Release commit: `4c6c12a90025b6f62f6f7d73bbf9ab810fd32b4e`
- Compiler artifact SHA-256: `71b46d37d9c2e24b4eabd67ed2491a33adbb1a039e7bc49ef1f7556870e6344d`
- Compared with: [`v0.66.0...v0.66.1`](https://github.com/flix/flix/compare/v0.66.0...v0.66.1)
- Rejected predecessor commit: `e6c02a151dff32a329c29dd8f83bb1067cf4cb30`
- Analyzer commit: `def7564`
- Java and platform: OpenJDK 21.0.12.1, Darwin arm64

## Upstream changes reviewed

The 0.66.1 adapter ABI is also structurally present in 0.66.0. The official 0.66.0 artifact cannot
run under the repository's required Java 21, however: `dev.flix.runtime.Global` has class-file
major version 68 (Java 24). The 0.66.1 artifact's runtime class has major version 65 and loads on
Java 21.

## Adapter impact

`Flix0661Adapter` is compiled in a separate Mill module against 0.66.1. It uses the older
`Input.TxtFile` and `Input.Text` representation and the pre-0.68 location/token representation.
Capability inspection now also verifies that the compiler runtime's `Global` class is loadable,
without initializing it, so 0.66.0 fails before the bridge attempts measurement.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/fetch-flix.sh` | official 0.66.1 artifact matched the recorded SHA-256 |
| Adapter source build | `./mill --no-server adapter.flix0661.compile` | passed with fatal Scala warnings |
| Capability boundary | `scripts/test-compiler-compatibility.sh` | 0.66.1 passed; 0.66.0 rejected its Java 24 runtime class |
| Semantic fixture | `scripts/test-one.sh Flix0661AdapterTest` | passed |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | passed cold/warm reports, cache, JSON, SARIF, and initialization |
| Normalized parity | full JSON comparison with 0.76.0 | only historical effect normalization differed |
| Corpus calibration | not rerun | current 0.76.0 pin and thresholds are unchanged |
| Full test suite | `make test` | passed after implementation |

## Decision and compatibility impact

- Supported release and adapter: Flix 0.66.1 through `Flix0661Adapter`; 0.66.0 is rejected.
- Metric parity: all fixture fields except effect normalization match 0.76.0. The older compiler
  reports `Assert` plus `IO`, so effect count and its summary/ranking are 2 rather than 1.
- Report schema, wire format, cache, and CLI: unchanged.
- SDK and capability JSON: JSON shape unchanged; engine capability now includes compiler-runtime
  loadability.
- Class-path implications: the compiler artifact remains compile-time-only and is not packaged.
