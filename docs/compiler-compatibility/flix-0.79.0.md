# Flix 0.79.0 compatibility investigation

## Inputs

- Release: [`v0.79.0`](https://github.com/flix/flix/releases/tag/v0.79.0)
- Release commit: [`36d923ca325dc466b28c63419667e6026a80f920`](https://github.com/flix/flix/commit/36d923ca325dc466b28c63419667e6026a80f920)
- Compiler artifact SHA-256: `8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf`
- Compared with: [`v0.78.0...v0.79.0`](https://github.com/flix/flix/compare/v0.78.0...v0.79.0)
- Analyzer implementation under test: `Flix0761Adapter` with the doc-comment start of a declaration restored (see below)
- Environment: OpenJDK 21.0.13, Darwin arm64

## Upstream changes reviewed

- `eff-check` and `eff-lock` without a package act on every dependency of the project, not on
  every installed package.
- `TypedAst.Case` and `TypedAst.StructField` gained a leading `doc` field. `javap` lists the same
  280 `TypedAst` constructors in 0.77.0 and 0.78.0; against 0.78.0 only these two differ. The
  adapter does not match on either.
- Source positions changed. From 0.79.0 (not 0.78.0, which is byte-identical to 0.77.0 here),
  `TypedAst.Def.loc` and `TypedAst.Effect.loc` of a declaration with a doc comment start at the
  `def` or `eff` line, or at its annotation, not at the first doc-comment line. The same source
  reports a later `line` and fewer `lines`. The span of 0.76.1 through 0.78.0 includes the doc
  comment and any blank or `//` lines between it and the declaration.
- The end of a definition moved too. Through 0.78.0 `Def.loc` ends at the last non-doc comment
  after the definition, up to the next token; from 0.79.0 it ends at the definition's last token.

## Adapter impact

The bytecode-derived gate accepts `Flix0761Adapter` on 0.79.0 with no missing members, so 0.79.0
joins the 0.76.1 linkage family. No new adapter or `Adapters.KNOWN` entry is needed.

Nothing beyond the 0.78.0 manifest rules applies to loading.

The position change moves what the adapter reports: `line`, `lines`, `definition-too-long`
(limit 60), the `longest` ranking, module `lines`, finding and SARIF positions,
`maxLocalParametersLine` and effect declaration lines. The corpus project `qual-effect-system`
shows the end shift: `canonEqConstr` (`= ???` followed by a commented-out body) spans 75 lines
through 0.78.0 and 1 on 0.79.0, so `definition-too-long` dropped from 27 to 26 findings. On a probe project with definitions of one,
three and five doc lines, 0.77.0 and 0.78.0 gave five `definition-too-long` findings and 0.79.0
gave two; module lines were 430 against 419. The adapter now starts a declaration at
`min(loc.startLine, doc.loc.startLine)` (`startOf`) and ends it at the last line of the non-doc
comments the lexer finds after the definition's last token (`endOf`), the same rules for every
release, so the spans of 0.76.1 through 0.78.0 are unchanged and 0.79.0 reports them too. `Doc.loc` keeps the
comment position in all releases. Not checked: enum and trait positions (they are only counted,
without a line) and adapters older than `Flix0761Adapter`.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/test-compiler-compatibility.sh` | matched the 0.79.0 release asset |
| Bytecode-derived ABI gate | packaged `capabilities` | `hasEngineApi=true`; no missing members |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | 0.60.0 through 0.79.0 checkpoints passed |
| Semantic fixture | `scripts/test.sh` with `plugin/lib/flix.jar` temporarily set to 0.79.0 | passed, 473 checks (before the position change) |
| Definition and effect spans | `DefinitionSpanTest` on 0.76.1, 0.77.0, 0.78.0, 0.79.0 | same `line` and `lines` on every release |
| Cross-release report | `scripts/test-compiler-compatibility.sh` | 0.77.0, 0.78.0 and 0.79.0 reports of one project agree; a 0.77.0 baseline gates a 0.79.0 run with 0 new, 0 resolved |
| Corpus calibration | `scripts/calibrate-corpus.sh` | not rerun on 0.79.0; see the decision below |

## Decision and compatibility impact

- Supported range and adapter: Flix 0.76.1 through 0.79.0 use `Flix0761Adapter`.
- Required implementation changes: `Flix0761Adapter.startOf` and `endOf` restore the doc-comment start
  of definitions and effect declarations and the trailing-comment end of definitions. The test fixtures also drop the manifest keys Flix 0.78
  rejects, and a mounted package must declare its `repository`.
- Metric parity and thresholds: without the compensation, totals are equal (the probe's report
  summary is byte-identical on 0.77.0, 0.78.0 and 0.79.0) but definition positions move on
  0.79.0. A probe with four definitions of 59 to 62 lines, each with two doc-comment lines,
  reports 59/60/61/62 `lines` and two `definition-too-long` findings (limit 60) on 0.77.0 and
  0.78.0, and 57/58/59/60 `lines` and none on 0.79.0; `line` values and the `longest` ranking
  shift the same way. With `startOf` the 0.77.0 measurements are reproduced on 0.79.0.
- Report schema, wire format, SDK and capability JSON, CLI: unchanged. The packaged adapter
  changed, so the plugin digest in the cache key invalidates existing cache entries.
- Baselines: finding identity (rule, subject, file) has no line, so existing baselines keep
  matching; measured values change only in 0.79.0 runs, and only back to the 0.77.0 values.
- Calibration pin: stays at 0.77.0. The nine corpus projects carry manifests with `name`,
  `description` or `authors`, which Flix 0.78.0 and later refuse to load, so the corpus cannot be
  measured by a 0.78+ compiler without rewriting pinned third-party sources.
- Follow-up work: decide whether calibration measures rewritten copies of the corpus manifests,
  and then move the `flix.jar` pin forward.
