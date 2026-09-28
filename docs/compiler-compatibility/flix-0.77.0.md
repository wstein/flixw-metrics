# Flix 0.77.0 compatibility investigation

## Inputs

- Release: [`v0.77.0`](https://github.com/flix/flix/releases/tag/v0.77.0)
- Release commit: [`4a5b60a31ac03bb762f68b554a0fc2b6f4d982b9`](https://github.com/flix/flix/commit/4a5b60a31ac03bb762f68b554a0fc2b6f4d982b9)
- Compiler artifact SHA-256: `20007d79f97b696ba388113e2a4235227691d33a00bfa669f2316b37a7b14201`
- Compared with: [`v0.76.1...v0.77.0`](https://github.com/flix/flix/compare/v0.76.1...v0.77.0)
- Analyzer implementation under test: `bcb55c5`, plus the calibration commit
- Environment: OpenJDK 21.0.12.1, Darwin arm64

## Upstream changes reviewed

The release note lists experimental polymorphic effects and improved HTML documentation. The
comparison is large (884 files), but most of it is a shortened license header. The compiler-facing
changes that intersect the adapter are:

- `TypedAst.StructField` gained a `mod` field. The adapter does not match on it.
- `@Export` and `TypedAstOps.isExport` were
  [removed](https://github.com/flix/flix/commit/0f4fe4b37). The adapter never consumed either.
- `Origin.Package` became `Origin.Package(id: PackageId)`, and `Flix` gained a mount table. The
  adapter matches only `Origin.isUser` and `Origin.Library`.
- Mounted packages are now
  [named under canonical roots](https://github.com/flix/flix/commit/89a68bdbe) of the form
  `$pkg$<host>$<owner>$<name>`, which cannot be written in source. Symbol `toString`
  [shows the root as the package identifier](https://github.com/flix/flix/commit/19d86480c), and a
  mounted package is [reachable only through `mount::`](https://github.com/flix/flix/commit/632c58697).
- Effect locking moved to a TOML lock file, and `flix.lock` became `packages.lock`.

No `TypedAst.Expr` alternative was added or removed; the set of 76 constructs is unchanged. The
`Typer` change is limited to threading the new struct-field modifiers. Prelude changed only its
license header.

## Adapter impact

The bytecode-derived gate accepts `Flix0761Adapter` on 0.77.0 with no missing members, so 0.77.0
joins the 0.76.1 linkage family. No new adapter or `Adapters.KNOWN` entry is needed.

The adapter now compiles against its family floor, `flix-0.76.1.jar`, instead of the moving
`flix.jar` pin. Without that, a reference that only 0.77.0 provides could build cleanly and then
fail to link on 0.76.1.

One semantic incompatibility was found. Module coupling joined raw namespace parts, so a project
calling a mounted package reported the dependency as `$pkg$github$flix$museum-clerk.Board`, while
Flix itself names it `github:flix/museum-clerk.Board`. The adapter now renders a canonical root the
way Flix does. It parses the root as a string rather than calling `PackageId.ofCanonicalRoot`,
because that API does not exist on 0.76.1. A new offline regression builds a mounted package in
`lib/` and asserts the rendered name. It also asserts that a package module stays distinct from a
same-named project module and that package definitions never enter project metrics.

No metric was added for struct-field modifiers, effect lock files, or package origin; none has a
demonstrated consumer.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest | `scripts/fetch-flix.sh` | matched the 0.77.0 release asset |
| Adapter source build | `make lint` | passed with fatal warnings, `Flix0761Adapter` compiled against 0.76.1 |
| Bytecode-derived ABI gate | packaged `capabilities` and `CompilerCapabilitiesTest` | `hasEngineApi=true`; no missing members |
| Semantic fixture | `scripts/test-one.sh Flix0761AdapterTest` | passed, including the new mounted-package regression, which failed before the fix |
| Packaged integration | `scripts/test-compiler-compatibility.sh` | 0.60.0 through 0.77.0 checkpoints passed; 0.59.0 and broken 0.66.0 rejected |
| Prelude parity | `StdlibCalibration` with 0.76.1 and 0.77.0 and their own Prelude | identical output apart from the file name |
| Corpus calibration | `scripts/calibrate-corpus.sh` | all 9 active targets matched exactly; 1,349 findings; 2 prior upstream-incompatible targets retained |
| Performance contract | `scripts/measure-performance.sh` | cold median 4,356 ms; warm 375 ms; 8% ratio |
| Packaged report schema | `scripts/validate-report-schema.sh` with `check-jsonschema` via `uvx` | passed |
| Full test suite | `make test` | passed, 473 checks |

No active calibration target depends on a Flix package, so the package-name fix does not move the
corpus. The mounted-package regression is the evidence for that change.

## Decision and compatibility impact

- Supported range and adapter: Flix 0.76.1 and 0.77.0 use `Flix0761Adapter`; Flix 0.75.3 and
  0.76.0 remain on `Flix0753Adapter`.
- Required implementation changes: compile the adapter against its 0.76.1 floor, and render mounted
  package roots by package identifier in module coupling.
- Metric parity and thresholds: all pinned corpus summaries and findings are unchanged; thresholds
  are unchanged. Projects that call mounted packages see readable dependency names instead of
  canonical roots; counts are unchanged.
- Report schema: unchanged.
- Wire format and cache: unchanged; compiler and plugin artifact bytes already separate cache entries.
- SDK and capability JSON: unchanged.
- CLI: unchanged.
- Class-path or dependency implications: none. Flix 0.77 writes `packages.lock` into fixture
  directories it resolves, which is now ignored.
- Follow-up work: none.
