# Flix 0.78.0 compatibility investigation

## Inputs

- Release: [`v0.78.0`](https://github.com/flix/flix/releases/tag/v0.78.0)
- Release commit: [`86e304dbe1d59afa114cd470621847aec3164d3c`](https://github.com/flix/flix/commit/86e304dbe1d59afa114cd470621847aec3164d3c)
- Compiler artifact SHA-256: `f6a8297a1fe269751aea6255ddd1549f64e74d80b5c6a99ac2a56201a7330957`
- Compared with: [`v0.77.0...v0.78.0`](https://github.com/flix/flix/compare/v0.77.0...v0.78.0)
- Analyzer implementation under test: `Flix0761Adapter` as shipped, unchanged
- Environment: OpenJDK 21.0.13, Darwin arm64

## Upstream changes reviewed

- `[package]` accepts only `version`, `repository` and `flix`. `name`, `description`, `authors`,
  `license` and `modules` are now rejected with `ManifestError.IllegalPackageKeyFound`, so a project
  that still carries them no longer loads.
- A dependency's manifest must declare the repository it was downloaded from
  (`PackageError.MissingRepository`).
- `HtmlDocumentor` moved to `tools.doc`, and `doc` prints a missing-documentation report.
- `TypedAst.Case` and `TypedAst.StructField` gained a `doc` field. The adapter does not match on
  either. No `TypedAst.Expr` alternative was added or removed.

## Adapter impact

The bytecode-derived gate accepts `Flix0761Adapter` on 0.78.0 with no missing members, so 0.78.0
joins the 0.76.1 linkage family. No new adapter or `Adapters.KNOWN` entry is needed.

Metrics measure the project in place, so a 0.78+ compiler reports its own manifest error for a
project that still has the old keys. That message reaches the user unchanged.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/test-compiler-compatibility.sh` | matched the 0.78.0 release asset |
| Bytecode-derived ABI gate | packaged `capabilities` | `hasEngineApi=true`; no missing members |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | 0.60.0 through 0.78.0 checkpoints passed |
| Semantic fixture | `scripts/test.sh` with `plugin/lib/flix.jar` temporarily set to 0.78.0 | passed, 473 checks |
| Corpus calibration | `scripts/calibrate-corpus.sh` | not rerun on 0.78.0; see the decision below |

## Decision and compatibility impact

- Supported range and adapter: Flix 0.76.1 through 0.78.0 use `Flix0761Adapter`.
- Required implementation changes: none in the adapter. The test fixtures now drop the manifest
  keys Flix 0.78 rejects, and a mounted package must declare its `repository`.
- Metric parity and thresholds: unchanged.
- Report schema, wire format, cache, SDK and capability JSON, CLI: unchanged.
- Calibration pin: stays at 0.77.0. The nine corpus projects carry manifests with `name`,
  `description` or `authors`, which Flix 0.78.0 and later refuse to load, so the corpus cannot be
  measured by a 0.78+ compiler without rewriting pinned third-party sources.
- Follow-up work: decide whether calibration measures rewritten copies of the corpus manifests,
  and then move the `flix.jar` pin forward.
