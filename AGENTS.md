# Repository Guidelines

## Project Structure & Module Organization

The analyzer is one mixed Java/Scala Mill module. Compiler-neutral reporting, configuration,
caching, and CLI code live in `plugin/src/dev/flixw/metrics/`. The version-specific Flix adapter
is isolated in `plugin/src/dev/flixw/metrics/flix075/`; the stable boundary is under `sdk/`.
Tests mirror the package in `plugin/test/src/` and use the real Flix fixture in
`plugin/test/fixtures/semantic/`. Calibration inputs and budgets live in `calibration/`, project
documentation in `docs/`, automation in `scripts/`, and CI workflows in `.github/workflows/`.
Do not commit generated `out/`, `dist/`, or the downloaded `plugin/lib/flix.jar`.

## Build, Test, and Development Commands

- `make lint` fetches the pinned compiler and compiles with all warnings fatal.
- `make test` runs linting, calibration and performance-contract tests, packaging checks, all
  executable unit tests, the real-compiler adapter fixture, and plugin integration tests.
- `make package` creates `dist/plugin.jar` and `dist/SHA256SUMS`.
- `make format` applies the repository formatter.
- `./mill plugin.docJar` builds Java/Scala API documentation.
- `sh scripts/calibrate-corpus.sh /tmp/results` remeasures the pinned external corpus; use it when
  changing adapter logic or default thresholds.

Java 21 is required. `./mill` bootstraps the pinned build tool; network access may be needed on the
first build to fetch Flix and dependencies.

## Coding Style & Naming Conventions

Use four-space indentation in Java and two spaces in Scala. Keep compiler types inside versioned
adapters; SDK records should contain only strings, numbers, booleans, paths, and collections.
Prefer named builders for wide records and exhaustive Scala matches for compiler AST logic.
Classes use `UpperCamelCase`, methods and fields use `lowerCamelCase`, and rule IDs use kebab-case,
for example `definition-too-long`. Run `make format` and `make lint` before committing.

## Testing Guidelines

Tests are dependency-free classes named `*Test.java` with a `main` method and exact assertions.
Add a failing regression before behavioral fixes. Use temporary directories and clean them in
`finally`. Adapter changes must extend `Flix075AdapterTest`; packaging or process changes should
extend `PluginIntegrationTest`. Run `make test` before opening a pull request. CodeQL supplements,
but does not replace, regression coverage.

## Commit & Pull Request Guidelines

Follow the existing focused subject style: `fix: ...`, `feat: ...`, `test: ...`, `docs: ...`,
`refactor: ...`, `perf: ...`, or `ci: ...`. Keep each commit independently buildable and limited
to one concern. Pull requests should explain the behavior change, cite calibration evidence for
metric or threshold changes, list verification commands, and note report-schema, wire-format, SDK,
or capability-JSON compatibility impacts. Screenshots are only useful for rendered documentation.
