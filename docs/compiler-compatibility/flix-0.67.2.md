# Flix 0.67.2 compatibility investigation

## Inputs

- Release: `v0.67.2`
- Release commit: `d06801e0eb5cf155a716dc1553f481407fecf544`
- Compiler artifact SHA-256: `3162ba033d77e481c8cd731441e1f279bd8f44e5f278b2b21632149c30f8ed3f`
- Compared with: [`v0.67.1...v0.67.2`](https://github.com/flix/flix/compare/v0.67.1...v0.67.2)
- Rejected predecessor commit: `feb96781590885d4474d49a91ac7dbf5b8f93aa1`
- Analyzer commit: `24aed00`
- Java and platform: OpenJDK 21.0.12.1, Darwin arm64

## Upstream changes reviewed

Flix 0.67.2 uses `SourceLocation.beginLine`/`beginCol` and token `sp1`/`sp2` positions, where the
0.68 family uses `startLine`/`startCol` and position-valued token `start`/`end`. It also has
`Input.RealFile` and `Input.VirtualFile`. The immediately preceding 0.67.1 artifact instead has
`Input.TxtFile` and `Input.Text`, so it cannot satisfy this adapter's source-selection contract.
A later investigation added `Flix0661Adapter` for that representation.

## Adapter impact

`Flix0672Adapter` is compiled in its own Mill module against the official 0.67.2 artifact. It
adapts the older location and token representations while returning the same compiler-neutral
`CompilerModel` records. Its contract is checked before construction and is ordered after the two
newer adapter families.

The normalized semantic-fixture report is not identical to 0.76.0. Flix 0.67.2 normalizes the
fixture's declared `Assert` effect to `Assert` plus `IO`; 0.76.0 reports only `Assert`. Consequently
`testSelectValue.effectCount`, `widestEffectSurface`, and the corresponding ranking are 2 instead
of 1. Every other normalized report field matched. This reflects compiler semantics, not adapter
drift, so the adapter preserves it rather than rewriting the compiler's typed effect set.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/fetch-flix.sh` | official 0.67.2 artifact matched the recorded SHA-256 |
| Adapter source build | `./mill --no-server adapter0672.compile` | passed with fatal Scala warnings |
| Bytecode-derived ABI gate | `scripts/test-compiler-compatibility.sh` | 0.67.2 passed; 0.67.1 failed on the expected input representation |
| Semantic fixture | `scripts/test-one.sh Flix0672AdapterTest` | passed, including old positions, lines, effects, Datalog, and module edges |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | passed cold/warm reports, cache, JSON, SARIF, and initialization |
| Cross-version report comparison | normalized full JSON versus 0.76.0 | only the compiler-normalized `Assert`/`IO` effect difference described above |
| Corpus calibration | not rerun | current pin and measurement policy are unchanged; no thresholds changed |
| Performance contract | covered by full suite | passed |
| Full test suite | `make test` | passed after implementation |

## Decision and compatibility impact

- Supported release and adapter: Flix 0.67.2 through `Flix0672Adapter`; 0.67.1 is rejected.
- Required implementation changes: isolated historical compile module, location/token adaptation,
  a third bytecode-derived contract, and version-aware semantic expectations.
- Report schema: unchanged.
- Wire format and cache: unchanged.
- SDK and capability JSON: unchanged.
- CLI: unchanged.
- Class-path or dependency implications: the adapter is packaged; its compiler artifact is not.
- Follow-up work: completed by `Flix0661Adapter`; 0.66.0 is now the rejected runtime predecessor.
