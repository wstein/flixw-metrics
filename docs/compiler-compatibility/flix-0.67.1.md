# Flix 0.67.1 compatibility investigation

## Inputs

- Release: `v0.67.1`
- Release commit: `feb96781590885d4474d49a91ac7dbf5b8f93aa1`
- Compiler artifact SHA-256: `2f888a5c1ca2b343915add0ca697d36167fd16ae82e61b37bc0be29d09a1d8c4`
- Compared with: [`v0.67.1...v0.67.2`](https://github.com/flix/flix/compare/v0.67.1...v0.67.2)
- Analyzer commit: `def7564`
- Java and platform: OpenJDK 21.0.12.1, Darwin arm64

## Upstream changes reviewed

Flix 0.67.1 uses `Input.TxtFile` and `Input.Text`; 0.67.2 replaces those project/compiler-source
shapes with `Input.RealFile` and `Input.VirtualFile`. Its location and token positions otherwise
remain in the pre-0.68 representation.

## Adapter impact

The requested release shares the `Flix0661Adapter` contract and implementation. The family is
named after its oldest fully runnable verified release, 0.66.1, rather than the requested checkpoint.
It remains after the 0.67.2 adapter in registry order.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/test-compiler-compatibility.sh` | official artifact matched the recorded SHA-256 |
| Bytecode-derived ABI gate | packaged `capabilities` | passed through `Flix0661Adapter` |
| Semantic fixture | adapter fixture plus packaged integration | passed |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | passed cold/warm reports, cache, JSON, SARIF, and initialization |
| Normalized parity | full JSON comparison with 0.76.0 | only historical effect normalization differed |
| Corpus calibration | not rerun | current pin and thresholds are unchanged |
| Full test suite | `make test` | passed after implementation |

## Decision and compatibility impact

- Supported release and adapter: Flix 0.67.1 through `Flix0661Adapter`.
- Metric parity: all fixture fields except effect normalization match 0.76.0. The older compiler
  reports `Assert` plus `IO`, so effect count and its summary/ranking are 2 rather than 1.
- Report schema, wire format, cache, SDK boundary, capability JSON shape, and CLI: unchanged.
- Class-path implications: both adapter and compiler-runtime compatibility are checked before use;
  no compiler artifact is packaged.
