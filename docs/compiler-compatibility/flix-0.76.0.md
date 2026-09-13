# Flix 0.76.0 compatibility investigation

## Inputs

- Release: [`v0.76.0`](https://github.com/flix/flix/releases/tag/v0.76.0)
- Release commit: [`f2d4678c20bff1242f4cad5e23144db91027b762`](https://github.com/flix/flix/commit/f2d4678c20bff1242f4cad5e23144db91027b762)
- Compiler artifact SHA-256: `d8d9a3870e199c03ed6364ea9430f56f67bfd38c332c411628a6a7cb88b2b0b4`
- Compared with: [`v0.75.3...v0.76.0`](https://github.com/flix/flix/compare/v0.75.3...v0.76.0)
- Analyzer implementation under test: `8e47297`, including the 0.76 regressions
- Environment: OpenJDK 21.0.12.1, Darwin arm64

## Upstream changes reviewed

Flix replaced loaded Java reflection objects with nominal descriptors throughout Java resolution
and code generation. On `TypedAst`, `InstanceOf` and `CatchRule` now carry `ClassDesc`, constructor
and method invocations carry `JavaMethod`, field operations carry `JavaField`, and `NewObject`
carries `JClass`. `TypeConstructor.Native` now carries a class descriptor and arity. `DefaultHandler`
also dropped its `handledEff` field. The representative changes are the commits for
[native types](https://github.com/flix/flix/commit/a3774904b1a2bbbb7caa732e7d68a79fad15d30f),
[fields](https://github.com/flix/flix/commit/a68e10e0b251c435942269e8f2461c6245d99c68),
[constructors](https://github.com/flix/flix/commit/aa6b8d02ea268a4e9ea587ff4102bdcab8c032b4),
[methods](https://github.com/flix/flix/commit/07b12aabbe97ee14d82565058a06048767f27eab),
and [classes](https://github.com/flix/flix/commit/844640a7d97864ce6b0a80ce5a3eacfb3915f75d).

No `TypedAst.Expr` alternative was added or removed. The `Root`, `Def`, `Spec`, formal-parameter,
branch, rule, constraint, predicate, `ApplyDef`, source-location, lexer, formatter, bootstrap, and
`Flix.check` members consumed by the adapter retained compatible descriptors. Scala remains
2.13.18.

The release JAR remains a fat, unshaded artifact. It adds Byte Buddy 1.18.11 and jsr305 3.0.2 to
the packages already visible on the bridge application class path. The existing flat-class-path
bridge was retained because it works; the Java resolver rewrite alone is not evidence that all
compiler and standard-library runtime loading can move back to an isolated loader.

## Adapter impact

The existing adapter is source- and binary-compatible. It is named `Flix0753Adapter` for the
oldest release satisfying its exact bytecode contract. No duplicate 0.76 adapter or
`Adapters.KNOWN` entry is needed.
The descriptor payloads are not metrics inputs, but a Java-interoperability regression now proves
that constructor, method, `instanceof`, and catch nodes do not disrupt generic descent or branch
measurement.

One semantic incompatibility was found. Flix 0.76 introduced polymorphic effects, and
`Type.effects` recursively includes effect constructors occurring inside type arguments. For the
declared effect `Outer[Inner[Int32]]`, the old adapter reported both `Inner` and `Outer`, even though
the surrounding effect formula contains one atomic `Outer` capability. `effectsOf` now stops at a
saturated effect constructor, preserving the existing SDK shape by reporting `Outer`, and a real
compiler regression locks in that behavior.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/fetch-flix.sh` | matched the release asset |
| Adapter source build | `make lint` | passed with fatal warnings |
| Bytecode-derived ABI gate | packaged `capabilities` and `CompilerCapabilitiesTest` | `hasEngineApi=true`; no missing members |
| Semantic fixture | `scripts/test-one.sh Flix0753AdapterTest` | passed, including polymorphic effects and Java descriptors |
| Packaged integration | `PluginIntegrationTest` with the 0.76 JAR | passed |
| Compatibility families | `scripts/test-compiler-compatibility.sh` | packaged integration passed on 0.66.1, 0.67.1, 0.67.2, 0.68.0, 0.75.2, and 0.75.3; 0.66.0 rejected |
| Corpus calibration | `scripts/calibrate-corpus.sh /tmp/flixw-calibration-076c` | all 9 active targets matched exactly |
| Performance contract | `scripts/measure-performance.sh /tmp/flixw-performance-076.json` | cold median 4,138 ms; warm 336 ms; 8% ratio |
| Packaged report schema | `check-jsonschema` via `uvx` | passed |
| Full test suite | `make test` | passed |

Two previously calibrated projects do not compile under Flix 0.76.0. They remain pinned with their
last 0.75.3 measurements in `calibration/corpus.json` as `incompatibleProjects`:

- `flix-basicdb` resolves `flix-time` 0.23.0, whose covariant Java return usages fail in the new
  descriptor-based resolver.
- `flix-parsec` predates public modules and is rejected by the new module accessibility checks.

These are compiler/source compatibility failures before the analyzer receives a typed root, not
metric drift. Prelude, four external projects, and four Datalog examples produced exactly their
previous stable summaries and findings.

## Decision and compatibility impact

- Verified current-family releases: Flix 0.75.3 and 0.76.0 through `Flix0753Adapter`.
  A later investigation added `Flix0680Adapter` for 0.68.0 and 0.75.2 and moved the locked
  rejected predecessor to 0.66.0; subsequent investigations added the older adapter families.
- Required implementation change: count saturated polymorphic effects atomically.
- Report schema: unchanged.
- Wire format and cache: unchanged; compiler artifact bytes already invalidate cached measurements.
- SDK and capability JSON: unchanged.
- CLI and plugin command ownership: unchanged. Flix's new native `stat` command does not collide
  with `metrics`; `hasNativeMetrics` continues to mean the historical `Metrics$` capability.
- Class path: no architectural change; Byte Buddy and jsr305 join the unshaded collision surface.
- Thresholds: unchanged; the active calibration targets showed no metric or finding drift.
