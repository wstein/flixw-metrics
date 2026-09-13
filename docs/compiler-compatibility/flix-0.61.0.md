# Flix 0.61.0 compatibility investigation

## Inputs

- Release: `v0.61.0`
- Release commit: `4cd972f3899c9759c343d179f320649d4a329a71`
- Compiler artifact SHA-256: `e024cd8a72d52d3553cf7fb6b18cb81e1315ad524d9bfab89e317d63dda04ab9`
- Verified family endpoint: Flix 0.65.0, commit `37cd1ffabe16254a707be126e66a5b8f80955619`, artifact SHA-256 `5cab00e9b5d30f48cd905f4971562e722621e1bc2d135077c67e86ff02c7cb13`
- Analyzer commit: `de8ac42`
- Java and platform: OpenJDK 21.0.12.1, Darwin arm64

## Adapter impact

Flix 0.61.0–0.65.0 uses `Validation.Success`/`Failure` for bootstrap and project checking, while
the next family uses `Result.Ok`/`Err`. Its type formatter also predates the `SymbolSet` parameter.
`Flix0610Adapter` is compiled in isolation against 0.61.0 and translates those APIs to the stable
`CompilerModel` boundary.

The standard library's test API also predates the `Assert` effect used by current Flix. A
`semantic-validation` fixture preserves equivalent definitions, lexer, Datalog, location, and test
annotation coverage using the historical Boolean-returning test convention.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digests | `scripts/fetch-flix.sh`, compatibility script | 0.61.0 and 0.65.0 matched recorded SHA-256 values |
| Adapter source build | `./mill --no-server adapter.flix0610.compile` | passed with fatal Scala warnings |
| Bytecode-derived ABI gate | packaged `capabilities` | passed on 0.61.0 and 0.65.0 |
| Semantic fixture | `scripts/test-one.sh Flix0610AdapterTest` | passed |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | passed on both verified endpoints |
| Common-source parity | normalized JSON against 0.76.0, excluding tests | byte-for-byte identical after provenance removal |
| Corpus calibration | not rerun | current pin and thresholds are unchanged |
| Full test suite | `make test` | passed after implementation |

## Decision and compatibility impact

- Supported releases and adapter: Flix 0.61.0 and 0.65.0 through `Flix0610Adapter`.
- Report schema, wire format, cache, SDK, capability JSON, and CLI: unchanged.
- Class-path implications: the historical compiler is compile-time-only and is not packaged.
- Follow-up: intermediate releases require their own evidence before being listed as verified.
