# Shared compiler SDK: boundaries

Status: owner decisions on the design questions recorded (section 8); remaining open points are marked UNKNOWN. Refs wstein/flixw-metrics#20.
Basis: `origin/main` at 419c9247687ef16d2a67c7b576ba76b9d06fd534. Line numbers were taken at 614c4a53bc07081217f1f260ff5feb6e1554107b and shifted
to this basis; each shifted range was checked to hold identical text at both revisions. Paths are relative to the
repository root unless stated. `L<n>` is a line number in that revision.

Decided by the owner (chartroom note "Plugins share one compiler SDK", 2026-11-08): one repo
`flixw-compiler-sdk` with modules `kit` and `flix-bridge`; plugins embed a fixed SDK version; delivery
as release asset with SHA-256; the seven adapters and their compiler JARs move into the SDK; the
solver-hook interface waits for the spike (wstein/flixw-mutate#1). This document does not reopen
those decisions. It names the files that move and the interfaces that result.

The owner then decided the eleven design questions (chartroom note, section "Decisions on the design
questions", and the comment on wstein/flixw-metrics#20, 2026-11-08). Section 8 records each decision.
Where a table below says "Decided" it cites that question.

Constraint (owner): the owner has no access to the Flix repository and upstream changes are not
expected. Everything runs against unchanged stock Flix compilers, so the per-capability linkage check
(section 4) is the only protection against a compiler change.

Naming (owner, 2026-11-08): the second module is `flix-bridge`, not `compiler`, because `compiler` is
mistaken for the Flix compiler. The module holds the adapters between the plugins and unchanged Flix
compilers; it contains no compiler code. The repository keeps the name `flixw-compiler-sdk`. Packages
are `dev.flixw.sdk.kit` and `dev.flixw.sdk.bridge` (section 8, Q1). Identifiers of the code
(`CompilerModel`, `compilerJar`, `FLIXW_COMPILER_*`) name the Flix compiler or existing code and keep
their names in this document.

Marker: UNKNOWN means not verified from a file or a run for this document.

## 1. Module `kit`: plugin shell, no Flix knowledge

| Concern | Source today | Moves as |
| --- | --- | --- |
| Read the flixw context: `FLIXW_ABI_VERSION` must be `1`, `FLIXW_PROJECT_ROOT`, `FLIXW_COMPILER_JAR`, `FLIXW_JAVA_HOME`, optional `FLIXW_PLUGIN_CACHE` | `plugin/src/dev/flixw/metrics/Main.java` L421-440 (`Context`), L442-445 (`Usage`) | `Context` record and `Usage` exception |
| Two-phase launch: outer JVM, bridge JVM with `-Xss64m` and `-cp plugin.jar:flix.jar` | `Main.java` L33-97, L245-277 (`spawnBridge`, `bridgeCommand`) | Launcher parameterised by main class and arguments. Reason for two JVMs: `docs/COMPILER-SDK.md` L155-180 |
| Inspection class loader (platform-parented `URLClassLoader`, no class initialisation) | `Main.java` L236-243 | `kit` helper |
| ABI check driver: compare every adapter contract with the pinned jar, keep the contract with fewest missing references, add `dev.flix.runtime.Global` | `plugin/src/dev/flixw/metrics/CompilerCapabilities.java` L27-43, L45-52 | Decided (Q11): the check needs `AdapterAbi.contracts()`, which belongs to `flix-bridge`, so this row moves in step 2 and lives in `flix-bridge`, not in `kit`. Issue #21 (Goal) and flixw-mutate#2 list the ABI check under `kit`; the owner decided the deviation and the decision supersedes both texts. The issue texts themselves are not changed by this document |
| `capabilities` command JSON (`compilerJar`, `hasFlixApi`, `hasEngineApi`, `missing`) | `CompilerCapabilities.java` L54-70 | `kit`. `hasNativeMetrics` (L42, probes `ca.uwaterloo.flix.tools.Metrics$`) is metrics-specific and stays in the plugin |
| Cache key and entry store: SHA-256 over plugin version, plugin artifact tree, compiler jar, `flix.toml`, sorted project-relative source paths with digests; atomic write; every failure is a miss | `plugin/src/dev/flixw/metrics/ResultCache.java` L60-87 (key), L89-123 (read, write), L125-155 (hashing) | Keyed store. The payload codec (`Wire`, version `Wire.java` L45) stays in the plugin. Decided (Q6): the SDK version and the adapter family join the key as explicit fields |
| Stable-input retry around measurement | `plugin/src/dev/flixw/metrics/StableInputs.java` L10-30 | Decided (Q11): stays in the metrics plugin and does not move. It calls `Metrics.projectFiles` (L28, L31; defined `Metrics.java` L621), a plugin class, and `Work` throws `CompilerModel.ModelFailure` (import L7, `Work` L20), a `flix-bridge` type; the plugin uses both modules, so no parameter is needed. `Main.java` L89-92 and L140-143 catch `ModelFailure` and stay in the plugin as well |
| Provenance: commit, dirty flag, timestamp, bounded `git` call | `plugin/src/dev/flixw/metrics/Provenance.java` L18-86 | Header record and git probe. Rendering (`Formats.java` L50-57, L342; `Metrics.java` L359) stays in the plugin |
| Atomic report output (`--output`) | `Main.java` L168-192 | `kit` |
| Exit codes: `1` when `--fail-on` or `--fail-on-new` crosses (L85, L131); `2` for usage, configuration, compiler or model failure, and `LinkageError` (L89-96, L132-147) | `Main.java` | One documented table in `kit`. `1` stays a plugin policy decision; `2` is shell-level |

Couplings to plugin classes (grep on `origin/main` over the classes of this table, the adapters and
`sdk/`; every occurrence): `StableInputs.java` L28 and L31 call `Metrics.projectFiles`;
`Provenance.java` L22-27 call `SourceMetrics.Smell.quote`; `ResultCache.java` L62 and
`Main.java` L260 and L270 use `Main.class` to locate the plugin artifact and to relaunch it;
`CompilerCapabilities.java` L42 probes `tools.Metrics$` (above); all seven adapters import
`DatalogGraph` (decided, Q2: it moves into `flix-bridge`). References to `Main.Context` and `Main.Usage` (`StableInputs.java`
L15, L25, L35; `CompilerCapabilities.java` L29, L54; `ResultCache.java` L49, L60, L69; `Provenance.java` L30)
disappear when `Context` and `Usage` move to `kit`. `sdk/*.java` references no plugin class. Each
coupling needs a parameter, an interface or a stay-in-plugin decision. Decided: `StableInputs` stays (Q11) and `DatalogGraph` moves (Q2). UNKNOWN: the cut for `SourceMetrics.Smell.quote` in `Provenance`, for `Main.class` in `ResultCache` and in the launcher, and for the `hasNativeMetrics` probe.

Not in `kit`: option parsing (`Main.java` L284-375), because the options are plugin-specific.

## 2. Module `flix-bridge`: adapters and version data

| Concern | Source today | Moves as |
| --- | --- | --- |
| Seven Scala adapters, one per ABI family: `Flix0600`, `Flix0610`, `Flix0661`, `Flix0672`, `Flix0680`, `Flix0753`, `Flix0761` (581-652 lines each, 4151 in total) | `adapter/flixNNNN/src/dev/flixw/metrics/adapter/FlixNNNNAdapter.scala` | Same files; packages renamed to `dev.flixw.sdk.bridge` (Q1). Decided (Q3, Q4): one class per capability and family; the existing class of each family is its `Metrics` class. UNKNOWN: whether the `Flix0NNNAdapter` class names stay |
| `FlixAdapter` build trait: one module per adapter, compiled against the oldest compiler of its family, compiler jar on `compileClasspath` only | `build.mill` L8-54 | Build file of `flix-bridge` |
| `AdapterAbi`: reads each adapter's class files including nested `$` classes, collects `ca/uwaterloo/flix/*` references, resolves them with `Class.forName(name, false, loader)` and an exact descriptor | `plugin/src/dev/flixw/metrics/sdk/AdapterAbi.java` L19-50 | `flix-bridge` |
| `Adapters`: registry `KNOWN` (newest first) and `resolve()` | `plugin/src/dev/flixw/metrics/sdk/Adapters.java` L28-35 (`KNOWN`), L38-53 (`resolve`), L56-58 (`known`) | `flix-bridge`; `KNOWN` is replaced by the version data file |
| Stable model `CompilerModel` and its records | `plugin/src/dev/flixw/metrics/sdk/CompilerModel.java` L33-61 (`SDK_VERSION = 1` at L50, `measure` at L61), records from L114 (`DefInfo`; `Model` at L370), `ModelFailure` L396 | `flix-bridge`, as the `Metrics` capability (section 4). Its records are metrics-shaped (definitions, effects, Datalog, lines); decided (Q3): the model splits per capability, so these records belong to `Metrics` only. UNKNOWN: the records of other capabilities |
| Compiler jars with SHA-256: the seven adapters compile against 0.76.1, 0.75.3, 0.68.0, 0.67.2, 0.66.1, 0.61.0, 0.60.0 (`build.mill` L31-49); 0.77.0 (`flix.jar`) and 0.76.0 are fetched for tests and the current pin, and no adapter compiles against them. The jars are not tracked (`.gitignore` ignores `lib/`) | `scripts/fetch-flix.sh` L12-29 (digests), L63-71 (fetch calls) | Script in `flix-bridge`; the digests move into `versions.json` (Q5) |
| Compatibility documents: table of 14 verified releases and the 0.59.0 and 0.66.0 rejections | `docs/compiler-compatibility/README.md`, `flix-*.md`, `template.md`; `docs/COMPILER-SDK.md` L1-114 (contract, linkage-based selection, ABI gate) | `flix-bridge`. Metrics-specific sections of `docs/COMPILER-SDK.md` (L182 onward, "What is measured") stay |
| Verification CI: official release jars must pass the ABI gate and packaged integration; 0.59.0 and 0.66.0 must fail | `scripts/test-compiler-compatibility.sh` L97-123, L165-171, L187-200; `scripts/check-lint-adapters.sh`; `.github/workflows/quality.yml` | `flix-bridge`. The packaged integration step runs a plugin; the SDK needs a fixture plugin or the conformance suite (section 6) |
| Version data as a file | Spread over six places today: `Adapters.KNOWN`, `build.mill` L31-49, `scripts/fetch-flix.sh` L12-29, `docs/compiler-compatibility/README.md` table, `plugin/resources/dev/flixw/metrics/verified-flix-versions.txt` (14 entries, read by `CompilerVersions`), the version loops in `scripts/test-compiler-compatibility.sh` (L165-171, L187-200); also prose in `AGENTS.md` | One data file; schema below |

Data file `flix-bridge/versions.json`. Decided (Q5): the file lives in the SDK repository and holds
the digest of each compiler jar per version. The field names below are a proposal. JSON follows from
`jq` being a build requirement already (`CONTRIBUTING.md`):

```text
{ "flix": "0.79.0", "family": "flix0761", "status": "verified",
  "capabilities": ["metrics"], "jar": {"sha256": "..."} }
```

Fields: Flix version, adapter family, status (`verified`, `rejected`, `unrecorded`), capabilities.
`build.mill`, `fetch-flix.sh`, the compatibility table, the CI loops and the registry derive from it.
Status values come from the existing documents: verified rows in the README table, rejected for
0.59.0 and 0.66.0 (`scripts/test-compiler-compatibility.sh` L97-114). `unrecorded` is proposed
(draft PRs #14, #15 and #17 concern unrecorded releases; contents not read for this document).

## 3. What stays in the plugins

In metrics: rules and thresholds (`RuleDefinitions.java`, `Thresholds.java`), smells and rankings
(`SourceMetrics.java`, `Rankings.java`), `Metrics`, `DocumentationMetrics`, baseline
comparison (`Baseline.java`), policy file (`MetricsConfig.java`), filters (`PresentationFilter.java`),
report formats text, json, md, sarif (`Formats.java`, `Metrics.java`), `Wire` payload codec,
`Initializer`, option parsing, and `docs/metrics-report.schema.json`.

In mutate: operators, the Datalog mutation engine, reports (`projects/flixw-mutate/docs/design/DESIGN.md`
L118-128 states the same rule: rules, operators and report formats stay in the plugins).

`DatalogGraph` is not in this list: it moves into `flix-bridge` (Q2), because all seven adapters call it.

Rule for the boundary: nothing under `ca.uwaterloo.flix` is referenced outside `flix-bridge`
(`AGENTS.md`, "Coding Style"); SDK records contain only strings, numbers, booleans, paths and
collections.

## 4. Capabilities

An adapter implements capabilities. The linkage check runs per capability, so a compiler can be
usable for one capability and not for another.

| Capability | Status | Basis |
| --- | --- | --- |
| `Metrics` | Exists | `CompilerModel.measure(Path)` returns `Model` (`CompilerModel.java` L61). Seven adapters implement it |
| `ProgramLoader` | Required by mutate; not in the code | DESIGN.md L118-124 lists "program loading" in the compiler layer. Metrics loads a project through `Bootstrap.bootstrap` (`COMPILER-SDK.md` L98) and discards the compiled program. Interface shape UNKNOWN |
| `TestRunner` | Required by mutate; not in the code | DESIGN.md L148 asks whether single tests run through internal `Tester` classes or whole suites per mutant. Open |
| `SolverHook` | Open until the spike wstein/flixw-mutate#1 | Bytecode rewrite or patched library source is UNKNOWN (chartroom note, "Open"; DESIGN.md L59-68). No interface is defined here |

Consequence for the code: `AdapterAbi.contract(name)` derives one contract per adapter class
(`AdapterAbi.java` L21-22, L41-44; `Adapters.java` L41-42). A per-capability check needs one class per
capability and family, so each class has its own bytecode-derived contract. Decided (Q4).

Why the selection is per capability: the owner has no access to the Flix repository and upstream
changes are not expected, so every capability runs against the unchanged stock compiler. A compiler
release that moves a reference of one capability cannot be corrected upstream; the per-capability
linkage check is the only protection, and it lets a compiler that lost one capability still serve
the others.

## 5. Three version levels and the report header

| Level | Owner of the number | Form | Where it lives |
| --- | --- | --- | --- |
| Flix | Compiler release | Data (`versions.json`) | `flix-bridge` |
| SDK | `flixw-compiler-sdk` | Semver, embedded in the plugin jar; additive evolution, removal only in a major version | `flix-bridge` and `kit` released together; `CompilerModel.SDK_VERSION` (L50) is declared and unused today |
| Plugin | Each plugin | Free | `Implementation-Version` of the jar, read by `Main.version()` (L279-282) |

Report header today (`Provenance.java` L18-19, `json()` L21-28): `commit`, `dirty`,
`analyzerVersion`, `measuredAt`, `compilerArtifact` (file name only, `Provenance.java` L35),
`inputDigest`. The schema accepts any object (`docs/metrics-report.schema.json` L21), so fields are
additive at the schema level; `Metrics.schemaVersion()` is 35 (`Metrics.java` L78-79) and each
additive change has raised it (`docs/COMPILER-SDK.md` L242-249, L279-280).

Proposed additional fields, all populated by `kit` and `flix-bridge`:

| Field | Meaning | Source |
| --- | --- | --- |
| `sdkVersion` | Embedded SDK semver; also a cache key field (Q6) | SDK build |
| `flixVersion` | Version of the pinned compiler | Decided (Q7): the environment variable `FLIXW_COMPILER_VERSION`, listed in flixw ABI 1 (`projects/flixw/docs/CONTRACT.md` L1168 on flixw `origin/main` 35a61cd8c0c83cecfafea34649c274046cbbda02) and set by flixw from its lock (`src/stage0/flixw.java` L6321-6322, inside `if (lock != null)`). No `lock.toml` read. Nothing in this repository reads it today: `Context` (`Main.java` L421-440) does not read it; `compilerArtifact` is only the jar file name. UNKNOWN: the value when flixw runs a plugin without a lock |
| `compilerDigest` | SHA-256 of the pinned jar | Computed already for the cache key (`ResultCache.java` L75), not reported |
| `adapter` | Chosen adapter class and family; the family is also a cache key field (Q6) | `Adapters.resolve()` |
| `capability` | Capability the report was produced with | Plugin |
| `skippedAdapters` | List of `{adapter, reason}` for each adapter that was tried and rejected, with the missing references | Not available today: `Adapters.resolve()` discards the result at L41-42 (`continue`) and swallows the exceptions at L46-50 |

`Main.java` L110-111 reports only the list of known adapters when none links. With
`skippedAdapters` the same failure names the missing references per adapter, which closes the gap
recorded in DESIGN.md L100 ("A skipped adapter and its reason are lost").

## 6. Conformance suite per adapter

Independent of any plugin, run in the SDK repository against every adapter family and every
verified Flix version of that family:

1. Linkage: the adapter's contract resolves in the matching jar (`missing` empty), and a jar outside
   the family fails (0.59.0, 0.66.0 as negative cases).
2. Capability behaviour on a fixed fixture: for `Metrics`, the fixed expected values now in
   `plugin/test/src/dev/flixw/metrics/Flix0NNNAdapterTest.java` (one per family) and the semantic fixture
   `plugin/test/fixtures/semantic/`. Flix 0.60.0, 0.61.0 and 0.65.0 run against
   `plugin/test/fixtures/semantic-validation` (`scripts/test-compiler-compatibility.sh` L187-189),
   so a fixture is chosen per family.
3. Cross-version parity within a family, as in the compatibility table (columns "Semantic fixture"
   and "Corpus").
4. Failure mode: an unlinkable jar produces the sentence and exit code `2`, not a stack trace
   (`Main.java` L132-139). The case where no adapter links is a separate sentence (`Main.java` L109-111, L140-143).

The dependency on metrics classes is in the adapter code, not in the tests: all seven adapters
import `dev.flixw.metrics.DatalogGraph` (`Flix0761Adapter.scala` L5), and every adapter module
compiles against `plugin.compile().classes` (`build.mill` L24). It has to move or be inverted
before the suite is independent. Decided (Q2): `DatalogGraph` moves into `flix-bridge` in step 2.

## 7. Migration order

Rule: regression first. Before each step the existing tests are green on the parent; after it they
are green without edits (`make test`, `AGENTS.md` "Testing Guidelines"). A test change in the same
PR as a move is not allowed except for package and import renames that a reviewer can verify
mechanically.

| Step | Issue | Content | Gate |
| --- | --- | --- | --- |
| 0 | #20 | This document | Owner decisions recorded in section 8 |
| 0a | owner | Repository `wstein/flixw-compiler-sdk` exists (blocker of #20) | Owner |
| 1 | #21 | Extract `kit` (section 1) without the ABI driver and `StableInputs`, which depend on `flix-bridge` or plugin types (direction and couplings below). Metrics consumes `kit` as an embedded, pinned release asset | Existing tests unchanged. Reports on the calibration corpus equal before and after, compared as `jq -S 'del(.provenance)'` (the pattern of `scripts/test-compiler-compatibility.sh` L226; `provenance.measuredAt` differs on every run, so byte equality is not a gate) |
| 2 | #22 | Move adapters, `DatalogGraph`, `AdapterAbi`, `Adapters`, `CompilerModel`, the ABI driver, `fetch-flix.sh`, compatibility documents and verification CI into `flix-bridge`; introduce `versions.json` | Existing adapter tests green and unedited apart from package renames. The conformance suite (section 6) runs in the SDK repository for all seven families, with an example plugin for the packaged check (Q8) and a test of the shared exit codes (Q10). `scripts/test-compiler-compatibility.sh` is split (below); both halves are green against the same version list |
| 3 | #23 | Capability-based selection and the new header fields | New tests first (failing against step 2), cache key change documented (explicit SDK version and family fields, Q6; `flixVersion` read from `FLIXW_COMPILER_VERSION`, Q7), schema version raised |
| 4 | none | `ProgramLoader`, `TestRunner` once mutate needs them; `SolverHook` after the spike. Also after the spike, from DESIGN.md L97-101 and L124: a run-time smoke test in the SDK that metrics can reuse, declared-member lookup for hook targets (`getDeclaredMethods`, paired with the smoke test), and a CI matrix over the version data that runs the smoke test | Not before the spike |

Dependency direction (decided, Q11): `flix-bridge` depends on `kit`; `kit` knows neither `flix-bridge`
nor Flix. Hence the ABI driver is not in step 1 but in `flix-bridge`, and `StableInputs` and the
`ModelFailure` handling stay in the plugin.

`scripts/test-compiler-compatibility.sh` needs `dist/plugin.jar` (L94) and `PluginIntegrationTest`
(L182-184), so it cannot run in the SDK repository as it is. Proposal for step 2: the ABI-gate part
(L97-171, official jars through the `capabilities` command, which also runs `dist/plugin.jar`, L94)
moves to the SDK repository and runs there through the conformance suite and the example plugin (Q8); the packaged-integration part (L182-200) stays in
`flixw-metrics` and runs against the embedded SDK. Whether the script splits at that line is
UNKNOWN.

Publication of the SDK release asset and the SHA-256 pin are owner operations (`AGENTS.md`, owner
controls artifact publication). Issue #24 (digest check of the cached compiler jar) touches
`scripts/test-compiler-compatibility.sh` and `scripts/fetch-flix.sh`; it lands before step 2 or
moves with it.

## 8. Decisions

Owner, 2026-11-08 (chartroom note, section "Decisions on the design questions"; comment on
wstein/flixw-metrics#20). They supersede conflicting text in #20, #21 and flixw-mutate#2. The numbers
are those of the questions in the first version of this document.

1. Q1, packages: `dev.flixw.sdk.kit` and `dev.flixw.sdk.bridge`. One cache miss on the rename is
   accepted: the plugin artifact tree is hashed (`ResultCache.java` L74), so every existing entry
   misses once. UNKNOWN: whether the `Flix0NNNAdapter` class names stay.
2. Q2, `DatalogGraph`: moves into `flix-bridge`. All seven adapters import it.
3. Q3, model: split per capability. `CompilerModel` and its records become the `Metrics` model.
4. Q4, classes: one class per capability and Flix family, so each has its own exact contract.
5. Q5, version data: `versions.json` in the SDK repository, with the digest of each compiler jar per
   version.
6. Q6, cache key: SDK version and adapter family join the key as explicit fields. The embedded SDK
   bytes are hashed already through the plugin artifact tree (`ResultCache.java` L74); the explicit
   fields make the key readable.
7. Q7, `flixVersion`: from `FLIXW_COMPILER_VERSION` (flixw ABI 1, `projects/flixw/docs/CONTRACT.md`
   L1168), no `lock.toml` read. Section 5 has the setter in flixw.
8. Q8, packaged check: the conformance suite plus a small example plugin.
9. Q9, flixw ABI v2: deferred until a third plugin exists.
10. Q10, exit codes: shared, owned by `kit`; the test of the codes joins the conformance suite.
    UNKNOWN: whether mutate's "compiler incompatible" (DESIGN.md L78) is covered by `2`; metrics
    uses `2` at `Main.java` L89-96, and no test checks it today.
11. Q11, direction: `flix-bridge` depends on `kit`; the ABI driver lives in `flix-bridge`;
    `StableInputs` stays in the metrics plugin. This deviates from #21 (Goal) and flixw-mutate#2,
    which name the ABI check under `kit`; the owner decided the deviation.

Still UNKNOWN after these decisions:

- The interfaces `ProgramLoader` and `TestRunner` (section 4).
- The `SolverHook` interface, until the spike wstein/flixw-mutate#1 (bytecode rewrite or patched
  library source).
- How `scripts/test-compiler-compatibility.sh` splits (section 7).
- How the remaining couplings of section 1 are cut (`Smell.quote`, `Main.class`, `hasNativeMetrics`).
- Whether the `Flix0NNNAdapter` class names stay (Q1).
- The value of `FLIXW_COMPILER_VERSION` when flixw runs a plugin without a lock (Q7).
- The records of capabilities other than `Metrics` (Q3).

## Appendix: `origin/main` and `refactor/adapter-modules`

Checked with `git rev-list --left-right --count`, `git cherry` and `git diff` (2026-11-08).

- `origin/refactor/adapter-modules` is 1c8fc3860494b1a74efaf396886000159151aea1, equal to the
  owner's local branch. It is 8 commits ahead of the merge base 37dfa9114bd12a3670612a801ced52d4c71c1cf8
  and 21 behind `origin/main`.
- `git cherry origin/main origin/refactor/adapter-modules` marks all 8 commits `-`: each has a
  patch-equivalent on `origin/main` (for example "define the Flix adapter modules once" is
  733dee8 on `origin/main`). The adapter build (`FlixAdapter` trait, `adapter.all`, seven modules)
  is the same on both.
- Content differences in `git diff origin/main origin/refactor/adapter-modules` (7 files, 27
  insertions, 439 deletions) exist only on `origin/main`: the Flix 0.79.0 declaration-position
  compensation in `Flix0761Adapter.scala` (`startOf`, `endOf`, `trailingEnd`), `DefinitionSpanTest`
  with its entries in `build.mill` (3 lines) and `scripts/test-one.sh` (3 lines), the 0.79.0 changes in
  `docs/compiler-compatibility/`, and a 48-line `span_report` block in
  `scripts/test-compiler-compatibility.sh`.
- Draft PRs #14 to #17 are on their own branches (`ci/flix-release-watch`,
  `docs/compat-matrix-and-checks`, `chore/calibration-corpus-0.78`, `feat/warn-unknown-flix`), not
  on `refactor/adapter-modules`. Their contents were not read for this document.
- Result: the SDK extraction starts from `origin/main`. The branch adds nothing to the adapter
  build that `origin/main` lacks.
