# Flix 0.79.0 compatibility investigation

## Inputs

- Release: [`v0.79.0`](https://github.com/flix/flix/releases/tag/v0.79.0)
- Release commit: [`36d923ca325dc466b28c63419667e6026a80f920`](https://github.com/flix/flix/commit/36d923ca325dc466b28c63419667e6026a80f920)
- Compiler artifact SHA-256: `8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf`
- Compared with: [`v0.78.0...v0.79.0`](https://github.com/flix/flix/compare/v0.78.0...v0.79.0)
- Analyzer implementation under test: `Flix0761Adapter` as shipped, unchanged
- Environment: OpenJDK 21.0.13, Darwin arm64

## Upstream changes reviewed

- `eff-check` and `eff-lock` without a package act on every dependency of the project, not on
  every installed package.
- `TypedAst.Case` and `TypedAst.StructField` gained a leading `doc` field. `javap` lists the same
  280 `TypedAst` constructors in 0.77.0 and 0.78.0; against 0.78.0 only these two differ. The
  adapter does not match on either.
- The source location of a definition that has a doc comment now starts at the `def` line, not at
  the first doc-comment line. The same source reports a later `line` and fewer `lines`.

## Adapter impact

The bytecode-derived gate accepts `Flix0761Adapter` on 0.79.0 with no missing members, so 0.79.0
joins the 0.76.1 linkage family. No new adapter or `Adapters.KNOWN` entry is needed.

Nothing beyond the 0.78.0 manifest rules applies.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/test-compiler-compatibility.sh` | matched the 0.79.0 release asset |
| Bytecode-derived ABI gate | packaged `capabilities` | `hasEngineApi=true`; no missing members |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | 0.60.0 through 0.79.0 checkpoints passed |
| Semantic fixture | `scripts/test.sh` with `plugin/lib/flix.jar` temporarily set to 0.79.0 | passed, 473 checks |
| Corpus calibration | `scripts/calibrate-corpus.sh` | not rerun on 0.79.0; see the decision below |

## Decision and compatibility impact

- Supported range and adapter: Flix 0.76.1 through 0.79.0 use `Flix0761Adapter`.
- Required implementation changes: none in the adapter. The test fixtures now drop the manifest
  keys Flix 0.78 rejects, and a mounted package must declare its `repository`.
- Metric parity and thresholds: totals are equal (the probe's report summary is byte-identical on
  0.77.0, 0.78.0 and 0.79.0), but definition positions move on 0.79.0. A probe with four
  definitions of 59 to 62 lines, each with two doc-comment lines, reports 59/60/61/62 `lines` and
  two `definition-too-long` findings (limit 60) on 0.77.0 and 0.78.0, and 57/58/59/60 `lines` and
  none on 0.79.0. `line` values and the `longest` ranking shift the same way. The adapter does not
  yet compensate; that is a follow-up PR, and the decision is to compensate.
- Report schema, wire format, cache, SDK and capability JSON, CLI: unchanged.
- Calibration pin: stays at 0.77.0. The nine corpus projects carry manifests with `name`,
  `description` or `authors`, which Flix 0.78.0 and later refuse to load, so the corpus cannot be
  measured by a 0.78+ compiler without rewriting pinned third-party sources.
- Follow-up work: decide whether calibration measures rewritten copies of the corpus manifests,
  and then move the `flix.jar` pin forward.
