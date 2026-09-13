# Flix 0.75.2 compatibility investigation

## Inputs

- Release: `v0.75.2`
- Release commit: `40949531b4d42e5eaf2e4b9997537eaf793c24e7`
- Compiler artifact SHA-256: `a2697d875725a0dde6e793b8d54cb220e86167a6d49ec5f0ccb0832966c8c15a`
- Compared with: [`v0.75.2...v0.75.3`](https://github.com/flix/flix/compare/v0.75.2...v0.75.3)
- Analyzer commit: `64083eb`
- Java and platform: OpenJDK 21.0.12.1, Darwin arm64

## Upstream changes reviewed

The relevant boundary is the 0.75.3 internal representation change. In 0.75.2,
`TypedAst.Spec.fparams` and `TypedAst.Expr.LocalDef.fparams` return Scala `List`; in 0.75.3 they
return Flix's `Nel`. That exact JVM descriptor change prevents `Flix0753Adapter` from safely
measuring 0.75.2 even though most of the AST is otherwise shared.

## Adapter impact

The adapter was first compiled directly against 0.75.2 to expose all source and descriptor
differences. Its implementation was then compiled against the oldest release satisfying the same
contract, 0.68.0, and named `Flix0680Adapter` according to the repository's oldest-release naming
rule. The newer adapter stays first in the registry, so 0.75.3 and 0.76.0 retain their newer
formal-parameter and polymorphic-effect behavior.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/test-compiler-compatibility.sh` | official 0.75.2 artifact matched the recorded SHA-256 |
| Adapter source build | direct 0.75.2 derivation, then `adapter0680.compile` | passed |
| Bytecode-derived ABI gate | packaged `capabilities` | older contract passed; newer contract was independently confirmed incompatible |
| Semantic fixture | `Flix0680AdapterTest` plus packaged integration | definitions, lines, effects, Datalog, and module edges passed |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | passed cold/warm reports, cache, JSON, SARIF, and initialization |
| Corpus calibration | not rerun | current 0.76.0 pin and thresholds are unchanged |
| Performance contract | covered by full suite | passed |
| Full test suite | `make test` | passed after implementation |

## Decision and compatibility impact

- Supported release and adapter: Flix 0.75.2 through `Flix0680Adapter`.
- Required implementation changes: a separately compiled adapter family and structural selection
  across multiple bytecode-derived contracts.
- Report schema: unchanged.
- Wire format and cache: unchanged.
- SDK and capability JSON: stable public measurement boundary and JSON shape unchanged.
- CLI: unchanged.
- Class-path or dependency implications: both adapters are packaged; neither compiler is packaged.
- Follow-up work: none for 0.75.2.
