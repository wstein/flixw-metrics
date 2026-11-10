# Flix 0.79.0 compatibility investigation

## Inputs

- Release: [`v0.79.0`](https://github.com/flix/flix/releases/tag/v0.79.0)
- Release commit: [`36d923ca325dc466b28c63419667e6026a80f920`](https://github.com/flix/flix/commit/36d923ca325dc466b28c63419667e6026a80f920)
- Compiler artifact SHA-256: `8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf`
- Compared with: [`v0.78.0...v0.79.0`](https://github.com/flix/flix/compare/v0.78.0...v0.79.0)
- Analyzer implementation under test: `Flix0761Adapter` with the doc-comment start of a declaration restored (see below)
- Original environment: OpenJDK 21.0.13, Darwin arm64
- Complete corpus rerun (2026-11-09): Temurin 21.0.13+9, Linux aarch64; analyzer source `d02ca1372196e9c0b5915af34a41cca7d701842e` (slash-banner span fix)

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
through 0.78.0 and 1 on 0.79.0, so `definition-too-long` dropped from 27 to 26 findings. The
adapter now starts a declaration at `min(loc.startLine, doc.loc.startLine)` (`startOf`) and ends
it at the last line of the non-doc comments that directly follow the definition's last token
(`endOf`; a later token on that line owns them), the same rules for every release. 0.79.0 then
reports the spans of 0.76.1 through 0.78.0: packaged 0.77.0 reports of five projects (four corpus
inputs and the semantic fixture) and two probe projects are identical before and after the change,
and 0.76.1, 0.77.0, 0.78.0 and 0.79.0 agree on the probes. `Doc.loc` keeps the comment position in
all releases. Not checked: enum and trait positions (they are only counted, without a line) and
adapters older than `Flix0761Adapter`.

The complete 2026-11-09 corpus comparison exposed a further trailing-span case: slash banners
(`////` or longer) are ordinary comments, not documentation. The old text-prefix check
stopped `endOf` at the banner after `FordFulkerson.indexOf`, giving 8 instead of 12 definition
lines and 624 instead of 628 module lines on 0.79.0. `endOf` now uses the lexer token kind
(`isCommentDoc`), restoring both values. The corpus-derived banner/real-doc regression passes
on 0.76.1, 0.77.0, 0.78.0 and 0.79.0; no new adapter is needed. The source-line category issue
was subsequently corrected across all seven adapter families;
see the [comment-kind recalibration](../CALIBRATION.md#compiler-comment-kind-recalibration-2026-11-10).
The original snapshot retains its historical category values.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/test-compiler-compatibility.sh` | matched the 0.79.0 release asset |
| Bytecode-derived ABI gate | packaged `capabilities` | `hasEngineApi=true`; no missing members |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | 0.60.0 through 0.79.0 checkpoints passed |
| Semantic fixture | `scripts/test.sh` with `plugin/lib/flix.jar` temporarily set to 0.79.0 | passed, 473 checks (before the position change) |
| Definition and effect spans | `DefinitionSpanTest` on 0.76.1, 0.77.0, 0.78.0, 0.79.0 | same `line` and `lines` on every release |
| Cross-release report | `scripts/test-compiler-compatibility.sh` | 0.77.0, 0.78.0 and 0.79.0 reports of one project agree; a 0.77.0 baseline gates a 0.79.0 run with 0 new, 0 resolved |
| Complete corpus rerun | `CALIBRATION_MANIFEST=calibration/corpus-0.78.json CALIBRATION_COMPILER_JAR=/path/to/flix-0.79.0.jar sh scripts/calibrate-corpus.sh /tmp/flixw-calibration-0.79.0` | 9/9 targets match, 1,349 findings, eight empty warm-cache baseline comparisons; eight full native JSON comparisons with 0.77.0 match without provenance, including module lines and positions; eight cross-release baselines and SARIF/native location comparisons pass; [snapshot](../CALIBRATION.md#flix-0790-calibration-snapshot-2026-11-09) |

## Decision and compatibility impact

- Supported range and adapter: Flix 0.76.1 through 0.79.0 use `Flix0761Adapter`.
- Required implementation changes: `Flix0761Adapter.startOf` and `endOf` restore the doc-comment
  start of definitions and effect declarations and the trailing-comment end of definitions. The
  test fixtures also drop the manifest keys Flix 0.78 rejects, and a mounted package must declare
  its `repository`.
- Metric parity and thresholds: without the compensation, totals are equal (the probe's report
  summary is byte-identical on 0.77.0, 0.78.0 and 0.79.0) but definition positions move on
  0.79.0. A probe with four definitions of 59 to 62 lines, each with two doc-comment lines,
  reports 59/60/61/62 `lines` and two `definition-too-long` findings (limit 60) on 0.77.0 and
  0.78.0, and 57/58/59/60 `lines` and none on 0.79.0; `line` values and the `longest` ranking
  shift the same way. With `startOf` and `endOf` the 0.77.0 measurements are reproduced on
  0.79.0.
- Report schema, wire format, SDK and capability JSON structure, CLI: unchanged. The derived ABI
  contract additionally references the existing `TokenKind.isCommentDoc` method. The packaged adapter
  changed, so the plugin digest in the cache key invalidates existing cache entries.
- Baselines: finding identity (rule, subject, file) has no line, so existing baselines keep
  matching; measured values change only in 0.79.0 runs, and only back to the 0.77.0 values.
- Calibration pin: stays at 0.77.0. The pinned corpus manifests carry `name`, `description` or
  `authors`, which Flix 0.78.0 and later refuse to load. A variant (`calibration/corpus-0.78.json`)
  rewrites a fresh checkout of four projects before measuring and measures 0.78.0 and 0.79.0; see
  [CALIBRATION.md](../CALIBRATION.md#corpus-variant-for-flix-078-and-later).
- Full corpus evidence: [2026-11-09 snapshot](../../calibration/results/flix-0.79.0.json), with exact compiler/source pins and per-target results. A trailing-span correction was needed; no new adapter or policy adjustment was needed.
- Follow-up work: decide whether to move the ordinary `flix.jar` test/calibration pin forward; this snapshot leaves it at 0.77.0.
