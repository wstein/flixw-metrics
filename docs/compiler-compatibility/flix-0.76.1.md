# Flix 0.76.1 compatibility investigation

## Inputs

- Release: [`v0.76.1`](https://github.com/flix/flix/releases/tag/v0.76.1)
- Release commit: [`0832e818521c524bacef1cd3c046e2f3f0db9db9`](https://github.com/flix/flix/commit/0832e818521c524bacef1cd3c046e2f3f0db9db9)
- Compiler artifact SHA-256: `b2ed2b7f49902e2dfe1b4f10e23d9a5cb085461e7e8c64909ca2b3abfa566e74`
- Compared with: [`v0.76.0...v0.76.1`](https://github.com/flix/flix/compare/v0.76.0...v0.76.1)
- Analyzer implementation under test: `d2fae95`, plus the compatibility and calibration commits
- Environment: OpenJDK 21.0.12.1, Darwin arm64

## Upstream changes reviewed

The release note says “Compiler: Restructure LSP internals,” but the compiler-facing source model
and construction API also changed. `Input` was removed. `Source.input` was replaced by
`Source.sourceName`, `Source.origin`, and `Source.sctx`; the new `SourceName` hierarchy represents
paths, URIs, and package entries, while `Origin` distinguishes user, library, package, and unknown
sources. Flix's own `Stat` command consequently changed its project-source predicate from matching
`Input.RealFile` to `source.origin.isUser`.

`Flix` now takes package and JAR inputs in its constructor, implements `AutoCloseable`, and exposes
`close()`. `Bootstrap.mkFlix(options, formatter)` is the supported construction path. The no-argument
`Flix` constructor used by the outgoing adapter no longer exists. `TypedAst.Root` also dropped
`availableClasses`, which this analyzer did not consume. The set of 76 `TypedAst.Expr` alternatives
and all metric-bearing expression shapes remained unchanged.

## Adapter impact

The bytecode-derived gate correctly rejects `Flix0753Adapter` on 0.76.1 because its references to
`Input`, `Source.input`, and `Flix.<init>()V` cannot resolve. A new `Flix0761Adapter` is therefore a
real linkage family, not a version-string alias.

The new adapter obtains the engine from `Bootstrap.mkFlix`, closes it in `finally`, and selects
project declarations with `Origin.isUser` plus normalized root containment. Compiler-source
calibration selects only `Origin.Library`. Its metric traversal is otherwise identical to the
0.75.3 family. The outgoing adapter is compiled against its family floor, Flix 0.75.3, and its
0.76-only semantic regression continues to run against Flix 0.76.0.

No LSP- or source-origin metric was added. Origin is an ownership boundary needed to preserve
existing project-versus-library semantics; it is not itself a demonstrated maintainability signal.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/fetch-flix.sh` | matched the 0.76.1 release asset |
| Adapter source build | `make lint` | passed with fatal warnings |
| Bytecode-derived ABI gate | `CompilerCapabilitiesTest` | seven contracts; each family floor satisfied only the appropriate newest contract |
| Semantic fixture | `scripts/test-one.sh Flix0761AdapterTest` | passed all metric regressions; `Origin.User` project filtering asserted explicitly |
| Outgoing semantic fixture | `scripts/test-one.sh Flix0753AdapterTest` with 0.76.0 | passed |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | 0.60.0 through 0.76.1 checkpoints passed; 0.59.0 and broken 0.66.0 rejected |
| Corpus calibration | `scripts/calibrate-corpus.sh /tmp/flixw-metrics-calibration-0761-final` | all 9 active targets matched exactly; 2 prior upstream-incompatible targets retained |
| Performance contract | `scripts/measure-performance.sh /tmp/flixw-metrics-performance-0761.json` | cold median 4,364 ms; warm 362 ms; 8% ratio |
| Full test suite | `make test` | passed, including reproducible packaging and 473 checks |

## Decision and compatibility impact

- Supported range and adapter: Flix 0.76.1 uses `Flix0761Adapter`; Flix 0.75.3 and 0.76.0 remain
  on `Flix0753Adapter`.
- Required implementation changes: new isolated adapter module, source-origin filtering,
  bootstrap-managed engine construction, deterministic engine cleanup, and newest-first registry entry.
- Metric parity and thresholds: all pinned corpus summaries and 1,349 findings are unchanged;
  thresholds are unchanged.
- Report schema: unchanged.
- Wire format and cache: unchanged; compiler and plugin artifact bytes already separate cache entries.
- SDK and capability JSON: SDK shape and JSON shape are unchanged; the derived contract inventory
  grows from six to seven adapters.
- CLI: unchanged.
- Class-path or dependency implications: no new plugin dependency and no compiler JAR is packaged.
- Follow-up work: none. Revisit the adapter only when a later compiler changes a referenced ABI or
  calibration reveals semantic drift.
