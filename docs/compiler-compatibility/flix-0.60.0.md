# Flix 0.60.0 compatibility investigation

## Inputs

- Release: `v0.60.0`
- Release commit: `493c8bb2c6654318b679604efd0e4d0feb3fb0a6`
- Compiler artifact SHA-256: `9920b107c9b4eb42bf17c6f556141b4cc8edbfffdf792de9a1bc5284505cd3c5`
- Compared with: [`v0.59.0...v0.60.0`](https://github.com/flix/flix/compare/v0.59.0...v0.60.0)
- Rejected predecessor: commit `ca1d1a487e89036e658c0c30d8bced23442cf6bc`, artifact SHA-256 `461d62d302faf937748fe789e2a098612cfc7661b53a9ed9d1c1810638c9ebc4`
- Analyzer commit: `4acb323`
- Java and platform: OpenJDK 21.0.12.1, Darwin arm64

## Adapter impact

Flix 0.60.0 predates `TypedAst.ExtMatchRule`, so the 0.61 adapter cannot link even though the
remaining bootstrap, formatter, input, location, and token representations match. `Flix0600Adapter`
removes only that nonexistent AST case and otherwise preserves the same traversal and stable model.

The immediately preceding 0.59.0 compiler lacks `Symbol.EffSym` and the tuple constructor arity
accessor required by effect and return-width metrics. It therefore fails the bytecode-derived
contract before measurement.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/fetch-flix.sh` | official 0.60.0 artifact matched the recorded SHA-256 |
| Adapter source build | `./mill --no-server flix0600.compile` | passed with fatal Scala warnings |
| ABI boundary | `scripts/test-compiler-compatibility.sh` | 0.60.0 passed; 0.59.0 failed on the expected effect/tuple AST contract |
| Semantic fixture | `scripts/test-one.sh Flix0600AdapterTest` | passed using the historical test convention |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | passed cold/warm reports, cache, JSON, SARIF, and initialization |
| Common-source parity | normalized full JSON versus 0.76.0, excluding version-specific tests | byte-for-byte identical after provenance removal |
| Corpus calibration | not rerun | current pin and thresholds are unchanged |
| Full test suite | `make test` | passed after implementation |

## Decision and compatibility impact

- Supported release and adapter: Flix 0.60.0 through `Flix0600Adapter`; 0.59.0 is rejected.
- Report schema, wire format, cache, SDK, capability JSON, and CLI: unchanged.
- Class-path implications: the historical compiler remains compile-time-only and is not packaged.
- Metric parity: exact on the common semantic source; test syntax is version-specific.
