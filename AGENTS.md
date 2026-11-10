# Repository Guidelines

## Project Structure & Module Organization

The analyzer is a mixed Java/Scala Mill build. Compiler-neutral code lives in
`plugin/src/dev/flixw/metrics/`. The Flix adapters, the stable `CompilerModel`/`Adapters` boundary,
the bytecode-derived ABI check and the version data are not in this repository: they are `flix-bridge`
(package `dev.flixw.sdk.bridge`) of the SDK repository `wstein/flixw-plugin-sdk`, embedded from the
exact commit named in `sdk-source.properties` (with the `kit` module). `build.mill` compiles each
adapter family in isolation, as `adapter.flix0761` through `adapter.flix0600`, from the pinned
`flix-bridge/adapter/<family>/src` and embeds everything in the one `plugin.jar`;
`scripts/check-sdk-source.sh` refuses a checkout whose HEAD or consumed bytes differ from the pin.
Changing an adapter, `CompilerModel` or `versions.json` is an SDK change followed by a new pin. Tests live in `plugin/test/src/` and use the Flix fixture in
`plugin/test/fixtures/semantic/`. Calibration inputs and budgets live in `calibration/`, project
documentation in `docs/`, automation in `scripts/`, and CI workflows in `.github/workflows/`.
Do not commit generated `out/`, `dist/`, or downloaded `plugin/lib/flix*.jar` files.

## Architecture

The Java/Scala split is load-bearing, not incidental. `Bootstrap`/`TypedAst`/etc. in `flix.jar` carry no
ABI compatibility promise between releases. All knowledge of those types is confined to one file
per family in the SDK's `flix-bridge/adapter/flixNNNN/src/dev/flixw/sdk/bridge/adapter/`, for example
`Flix0753Adapter.scala`. Everything else — `Main`, `Metrics`, `ResultCache`, `SourceMetrics`, and
the stable `CompilerModel`/`Adapters` boundary of `flix-bridge` — stays plain Java that knows nothing about Flix.
`CompilerModel` returns counts and strings only, deliberately, never compiler types or an AST cursor.

The engine is Scala specifically because Flix's AST is a sealed hierarchy: typed patterns make removed
or misspelled nodes build failures. The metric classifier deliberately has a catch-all and generic
`Product` descent, so release-specific semantic fixtures and corpus comparison remain required. The
reflective predecessor classified nodes by simple class name and silently ignored unrecognized ones — a
`TypeMatchRule` that didn't exist was counted and the real `ExtMatchRule` was missed, with no error
anywhere. That failure mode is why the AST-facing half of the plugin is Scala at all; see
`docs/COMPILER-SDK.md` and the SDK's `flix-bridge/docs/compatibility/` for the full contract and release evidence.

Which releases each adapter serves is listed in `docs/COMPILER-SDK.md`; the verified list itself is
`flix-bridge/resources/dev/flixw/sdk/bridge/versions.json` of the pinned SDK (read here with
`scripts/sdk-versions.sh`; `scripts/fetch-flix.sh` takes the releases and their digests from it). Each
adapter name records the oldest verified release in its linkage family.
Supporting an incompatible Flix generation means adding one adapter class and a family entry in the
SDK's `versions.json` — never touching the report, findings, formats, cache, or CLI. Adapters are
selected by **linkage, not version string**: `Adapters.select()` checks each adapter's
bytecode-derived contract before instantiation and keeps the first compatible adapter, so lazy JVM
resolution cannot defer an incompatible AST failure until mid-measurement.

`CompilerFacade` is the plugin's side of the SDK's `CompilerCapabilities`: the bridge inspects the
pinned compiler jar via `Class.forName(name, false, loader)` (never initializing/running compiler
code) and `AdapterAbi` derives its exact class, field, constructor, and method requirements from
every compiled adapter's bytecode, including generated nested classes; the facade adds the
native-`Metrics` probe, the `capabilities` JSON and the unverified-release notice. A regression checks
each pinned compiler against these structural contracts; do not replace them with handwritten lists.

The compiler loads on the **application class path** of a second ("bridge") JVM (spawned by `Main`
via `--bridge` with `-cp plugin.jar:flix.jar`) launched with a 64 MiB thread stack (`-Xss64m`; Flix's
constraint generation is recursive and overflows the default stack on real projects). An isolated
`URLClassLoader` was tried and doesn't work — the Flix standard library resolves some of its own Java
dependencies (e.g. `dev.flix.runtime.Global`) through the application class path regardless of which
loader defined the compiler classes. Consequence: `flix.jar` bundles ASM, JLine, gson, json4s, and in
0.76 Byte Buddy and jsr305, **unshaded** on that flat class path, so any dependency this plugin adds
must be shaded or classpath order decides which copy wins.

Each adapter's floor jar (`flix-<version>.jar` in `plugin/lib`, named by `versions.json`) is wired into that adapter module
via `compileClasspath` in `build.mill`, not `unmanagedClasspath` — it must stay compile-time only.
The compiler-neutral `plugin` module has no Flix jar on its compile class path. At runtime the
compiler is whatever flixw pinned for the target
project; bundling a copy would mean measuring one compiler while claiming to measure another.

Only **measurements** are cached (`ResultCache`/`Wire`), keyed on sources, `flix.toml`, the pinned
compiler, this plugin's version, its own artifact bytes, and the bridge's `sdkVersion` and
`adapterFamily` as explicit fields (`Adapters.Selection.cacheFields()` in the measuring phase,
`CompilerFacade.cacheFields` in the outer phase, which must agree). Findings and formatting are recomputed on
every run — including cache hits — so an edited `.flixw-metrics.properties` policy or a fresh git
provenance snapshot takes effect immediately without invalidating the cache. Every cache failure is
treated as a miss, never a stale answer.

One `Report` object renders to `text`/`json`/`md`/`sarif`. `Thresholds` is the single place a measurement
becomes a finding; `RuleDefinitions` is the single catalog of rule IDs, severities, default limits, and
remediation text. A finding's `detail` string is always derived from its numeric fields, never stored
independently, so the two can't drift apart.

## Build, Test, and Development Commands

- `make lint` fetches the pinned compiler and compiles with all warnings fatal.
- `make test` runs linting, calibration and performance-contract tests, all executable unit tests,
  the real-compiler fixture, integration tests, the 0.59.0/0.60.0 AST boundary, and the known
  incompatible 0.66.0 runtime artifact. It also verifies packaged operation on every verified
  release. `scripts/test-flix-sample.sh` compares the
  totals and one definition position of a sample project on the 0.78.0 and 0.79.0 jars with
  golden files, and `scripts/test-unknown-flix.sh` pins the warning for a Flix release without
  a checkpoint. A compiler jar named `flix.jar`, such as the pin in `plugin/lib`, has no readable
  version in its name; the plugin takes the version from `FLIXW_COMPILER_VERSION`, which flixw sets,
  and only a run without it prints the `cannot read the Flix version` warning on stderr.
  Historical compiler jars are downloaded into ignored `out/` storage when absent.
  Its packaging
  check deliberately builds twice with a two-second gap to prove reproducibility; that pause is expected.
- `sh scripts/test-one.sh MetricsTest` compiles as needed and runs one test main. The wrapper also supplies
  the special fixtures and classpaths required by `CompilerCapabilitiesTest`, the adapter tests, and
  `PluginIntegrationTest`.
- `make package` creates `dist/plugin.jar` and `dist/SHA256SUMS`.
- `sh scripts/validate-report-schema.sh` validates a packaged fixture report with
  `check-jsonschema`; use it after changing native JSON or its schema.
- `make format` checks for trailing whitespace and formatting violations (no auto-formatter is configured).
- `./mill --no-server plugin.test.compile` quickly compiles sources and test suites.
- `./mill plugin.docJar` builds Java/Scala API documentation.
- `sh scripts/calibrate-corpus.sh /tmp/results` remeasures the pinned external corpus; use it when
  changing adapter logic or default thresholds.

Java 21, `jq`, `unzip`, and `shasum` are required. `./mill` bootstraps the build; the first build fetches Flix and dependencies.

## Coding Style & Naming Conventions

Use four-space indentation in Java and two spaces in Scala. Keep compiler types inside versioned
adapters; SDK records should contain only strings, numbers, booleans, paths, and collections.
Prefer named builders for wide records and exhaustive Scala matches for compiler AST logic.
Classes use `UpperCamelCase`, methods and fields use `lowerCamelCase`, and rule IDs use kebab-case,
for example `definition-too-long`. Run `make format` and `make lint` before committing.

## Testing Guidelines

Tests are dependency-free classes named `*Test.java` with a `main` method and exact assertions.
Add a failing regression before behavioral fixes. Use temporary directories and clean them in
`finally`. Adapter changes must extend the matching `Flix*AdapterTest`; packaging or process changes should
extend `PluginIntegrationTest`. Use `scripts/test-one.sh` while iterating; `./mill plugin.test.test`
does not exist because `testFramework = "none"`. Run `make test` before opening a pull request. CodeQL supplements,
but does not replace, regression coverage.

## Commit & Pull Request Guidelines

Always make focused, atomic commits. Follow the existing conventional subject style: `fix: ...`,
`feat: ...`, `test: ...`, `docs: ...`, `refactor: ...`, `perf: ...`, or `ci: ...`. Keep each commit
independently buildable and strictly limited to one concern.

- **Commit each logical step immediately**: As soon as a logical step is completed and verified,
  create its Conventional Commit before starting the next step.
- **Never batch unrelated work**: Do not accumulate unrelated edits, fixes, or multiple task phases
  in the working tree.
- **Pull request requirements**: Pull requests should explain the behavior change, cite calibration
  evidence for metric or threshold changes, list verification commands, and note report-schema,
  wire-format, SDK, or capability-JSON compatibility impacts. Screenshots are only useful for
  rendered documentation.
