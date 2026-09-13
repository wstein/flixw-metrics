# Flix 0.68.0 compatibility investigation

## Inputs

- Release: `v0.68.0`
- Release commit: `585a87879314a9f6519d9a0a7122e6a7a9ecbdad`
- Compiler artifact SHA-256: `af568a2d4046207f908f8ec37786409b3dccdec944c2df5f571444599fb6b7c8`
- Compared with: [`v0.67.0...v0.68.0`](https://github.com/flix/flix/compare/v0.67.0...v0.68.0)
- Analyzer commit: `64083eb`
- Java and platform: OpenJDK 21.0.12.1, Darwin arm64

## Upstream changes reviewed

The compatibility search tested selected official release artifacts spanning 0.50.0 through 0.75.2. The
bytecode-derived contract first becomes complete at 0.68.0. Flix 0.67.0 lacks the source-location
and token accessors used for declaration locations and lexer-backed line metrics, including
`SourceLocation.startLine()` and `Token.end(): SourcePosition`.

## Adapter impact

`Flix0680Adapter` is compiled in a separate Mill module against the 0.68.0 artifact. Its AST logic
also links on 0.75.2, but it cannot share the 0.75.3 adapter: Flix 0.75.3 changed formal-parameter
collections from `List` to `Nel`. The adapter keeps the older effect decomposition because
`Type.Apply.baseType` is not present in this family.

Resolver construction was not a sufficient compatibility test because JVM method references may
resolve lazily. Each registered adapter now has its own contract derived from its compiled class
tree, and resolution checks that contract before instantiation.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/fetch-flix.sh` | official 0.68.0 artifact matched the recorded SHA-256 |
| Adapter source build | `./mill --no-server adapter0680.compile` | passed with fatal Scala warnings |
| Bytecode-derived ABI gate | `scripts/test-compiler-compatibility.sh` | 0.68.0 passed; 0.67.0 failed on the expected location/token ABI |
| Semantic fixture | `scripts/test-one.sh Flix0680AdapterTest` | passed, including locations, lexer lines, effects, local definitions, Datalog, and module edges |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | passed cold/warm reports, cache, JSON, SARIF, and initialization |
| Corpus calibration | not rerun | current pin and measurement policy are unchanged; no thresholds changed |
| Performance contract | covered by full suite | no implementation outside adapter selection changed |
| Full test suite | `make test` | passed after implementation |

## Decision and compatibility impact

- Supported release and adapter: Flix 0.68.0 through `Flix0680Adapter`. A later investigation
  added `Flix0672Adapter` for 0.67.2 and `Flix0661Adapter` for 0.66.1 and 0.67.1; 0.66.0 is
  the rejected runtime predecessor.
- Required implementation changes: isolated historical compile module, second packaged adapter,
  multi-contract ABI inspection, and pre-instantiation resolver gating.
- Report schema: unchanged.
- Wire format and cache: unchanged.
- SDK and capability JSON: `CompilerModel` and capability JSON unchanged; `AdapterAbi` now models
  multiple contracts internally.
- CLI: unchanged.
- Class-path or dependency implications: neither compiler artifact is packaged; runtime still uses
  only the compiler selected by flixw.
- Follow-up work: investigate and record intermediate releases before calling them verified.
