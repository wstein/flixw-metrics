# Shared compiler SDK: boundaries

Status: proposal for owner decision. Refs wstein/flixw-metrics#20.
Basis: `origin/main` at 614c4a53bc07081217f1f260ff5feb6e1554107b. Paths are relative to the
repository root unless stated. `L<n>` is a line number in that revision.

Decided by the owner (chartroom note "Plugins share one compiler SDK", 2026-11-08): one repo
`flixw-compiler-sdk` with modules `kit` and `compiler`; plugins embed a fixed SDK version; delivery
as release asset with SHA-256; the seven adapters and their compiler JARs move into the SDK; the
solver-hook interface waits for the spike (wstein/flixw-mutate#1). This document does not reopen
those decisions. It names the files that move and the interfaces that result.

Marker: UNKNOWN means not verified from a file or a run for this document.

## 1. Module `kit`: plugin shell, no Flix knowledge

| Concern | Source today | Moves as |
| --- | --- | --- |
| Read the flixw context: `FLIXW_ABI_VERSION` must be `1`, `FLIXW_PROJECT_ROOT`, `FLIXW_COMPILER_JAR`, `FLIXW_JAVA_HOME`, optional `FLIXW_PLUGIN_CACHE` | `plugin/src/dev/flixw/metrics/Main.java` L419-438 (`Context`), L440-443 (`Usage`) | `Context` record and `Usage` exception |
| Two-phase launch: outer JVM, bridge JVM with `-Xss64m` and `-cp plugin.jar:flix.jar` | `Main.java` L33-95, L243-275 (`spawnBridge`, `bridgeCommand`) | Launcher parameterised by main class and arguments. Reason for two JVMs: `docs/COMPILER-SDK.md` L116-141 |
| Inspection class loader (platform-parented `URLClassLoader`, no class initialisation) | `Main.java` L234-241 | `kit` helper |
| ABI check driver: compare every adapter contract with the pinned jar, keep the contract with fewest missing references, add `dev.flix.runtime.Global` | `plugin/src/dev/flixw/metrics/CompilerCapabilities.java` L27-43, L45-52 | Design proposal, direction in section 7: the check needs `AdapterAbi.contracts()`, which belongs to `compiler`, so this row moves in step 2 and lives in `compiler`, not in `kit`. This differs from issue #21 (Goal) and flixw-mutate#2, which list the ABI check under `kit`; the issue texts need adjusting after the owner decides |
| `capabilities` command JSON (`compilerJar`, `hasFlixApi`, `hasEngineApi`, `missing`) | `CompilerCapabilities.java` L54-70 | `kit`. `hasNativeMetrics` (L42, probes `ca.uwaterloo.flix.tools.Metrics$`) is metrics-specific and stays in the plugin |
| Cache key and entry store: SHA-256 over plugin version, plugin artifact tree, compiler jar, `flix.toml`, sorted project-relative source paths with digests; atomic write; every failure is a miss | `plugin/src/dev/flixw/metrics/ResultCache.java` L60-87 (key), L89-123 (read, write), L125-155 (hashing) | Keyed store. The payload codec (`Wire`, version `Wire.java` L45) stays in the plugin. Whether the key also takes the SDK version and the adapter family is question 6 |
| Stable-input retry around measurement | `plugin/src/dev/flixw/metrics/StableInputs.java` L10-30 | Design proposal, not decided: it calls `Metrics.projectFiles` (L28, L31; defined `Metrics.java` L621), a plugin class, and `Work` throws `CompilerModel.ModelFailure` (import L7, `Work` L20), a `compiler` type. Either it stays in the plugin, or the source lister is passed as a parameter and the row moves with `CompilerModel` in step 2 (question 11). `Main.java` L87-90 and L138-141 catch `ModelFailure` and follow the same choice |
| Provenance: commit, dirty flag, timestamp, bounded `git` call | `plugin/src/dev/flixw/metrics/Provenance.java` L18-86 | Header record and git probe. Rendering (`Formats.java` L50-57, L342; `Metrics.java` L359) stays in the plugin |
| Atomic report output (`--output`) | `Main.java` L166-190 | `kit` |
| Exit codes: `1` when `--fail-on` or `--fail-on-new` crosses (L83, L129); `2` for usage, configuration, compiler or model failure, and `LinkageError` (L87-94, L130-145) | `Main.java` | One documented table in `kit`. `1` stays a plugin policy decision; `2` is shell-level |

Couplings to plugin classes (grep on `origin/main` over the classes of this table, the adapters and
`sdk/`; every occurrence): `StableInputs.java` L28 and L31 call `Metrics.projectFiles`;
`Provenance.java` L22-27 call `SourceMetrics.Smell.quote`; `ResultCache.java` L62 and
`Main.java` L258 and L268 use `Main.class` to locate the plugin artifact and to relaunch it;
`CompilerCapabilities.java` L42 probes `tools.Metrics$` (above); all seven adapters import
`DatalogGraph` (question 2). References to `Main.Context` and `Main.Usage` (`StableInputs.java`
L15, L25, L35; `CompilerCapabilities.java` L29, L54; `ResultCache.java` L49, L60, L69; `Provenance.java` L30)
disappear when `Context` and `Usage` move to `kit`. `sdk/*.java` references no plugin class. Each
coupling needs a parameter, an interface or a stay-in-plugin decision; none is decided here.

Not in `kit`: option parsing (`Main.java` L282-373), because the options are plugin-specific.

## 2. Module `compiler`: adapters and version data

| Concern | Source today | Moves as |
| --- | --- | --- |
| Seven Scala adapters, one per ABI family: `Flix0600`, `Flix0610`, `Flix0661`, `Flix0672`, `Flix0680`, `Flix0753`, `Flix0761` (581-652 lines each, 4151 in total) | `adapter/flixNNNN/src/dev/flixw/metrics/adapter/FlixNNNNAdapter.scala` | Same files; package renamed (see section 8, question 1) |
| `FlixAdapter` build trait: one module per adapter, compiled against the oldest compiler of its family, compiler jar on `compileClasspath` only | `build.mill` L8-54 | Build file of `compiler` |
| `AdapterAbi`: reads each adapter's class files including nested `$` classes, collects `ca/uwaterloo/flix/*` references, resolves them with `Class.forName(name, false, loader)` and an exact descriptor | `plugin/src/dev/flixw/metrics/sdk/AdapterAbi.java` L19-50 | `compiler` |
| `Adapters`: registry `KNOWN` (newest first) and `resolve()` | `plugin/src/dev/flixw/metrics/sdk/Adapters.java` L28-35 (`KNOWN`), L38-53 (`resolve`), L56-58 (`known`) | `compiler`; `KNOWN` is replaced by the version data file |
| Stable model `CompilerModel` and its records | `plugin/src/dev/flixw/metrics/sdk/CompilerModel.java` L33-61 (`SDK_VERSION = 1` at L50, `measure` at L61), records from L114 (`DefInfo`; `Model` at L370), `ModelFailure` L396 | `compiler`, as the `Metrics` capability (section 4). Its records are metrics-shaped (definitions, effects, Datalog, lines) |
| Compiler jars with SHA-256: the seven adapters compile against 0.76.1, 0.75.3, 0.68.0, 0.67.2, 0.66.1, 0.61.0, 0.60.0 (`build.mill` L31-49); 0.77.0 (`flix.jar`) and 0.76.0 are fetched for tests and the current pin, and no adapter compiles against them. The jars are not tracked (`.gitignore` ignores `lib/`) | `scripts/fetch-flix.sh` L12-29 (digests), L63-71 (fetch calls) | Script and digests in `compiler` |
| Compatibility documents: table of 14 verified releases and the 0.59.0 and 0.66.0 rejections | `docs/compiler-compatibility/README.md`, `flix-*.md`, `template.md`; `docs/COMPILER-SDK.md` L1-114 (contract, linkage-based selection, ABI gate) | `compiler`. Metrics-specific sections of `docs/COMPILER-SDK.md` (L143 onward, "What is measured") stay |
| Verification CI: official release jars must pass the ABI gate and packaged integration; 0.59.0 and 0.66.0 must fail | `scripts/test-compiler-compatibility.sh` L76-102, L144-150, L166-179; `scripts/check-lint-adapters.sh`; `.github/workflows/quality.yml` | `compiler`. The packaged integration step runs a plugin; the SDK needs a fixture plugin or the conformance suite (section 6) |
| Version data as a file | Spread over five places today: `Adapters.KNOWN`, `build.mill` L31-49, `scripts/fetch-flix.sh` L12-29, `docs/compiler-compatibility/README.md` table, the version loops in `scripts/test-compiler-compatibility.sh` (L144-150, L166-179); also prose in `AGENTS.md` | One data file; schema below |

Proposed data file `compiler/versions.json` (format is UNKNOWN until decided; JSON is proposed
because `jq` is already a build requirement, `CONTRIBUTING.md`):

```text
{ "flix": "0.79.0", "family": "flix0761", "status": "verified",
  "capabilities": ["metrics"], "jar": {"sha256": "..."} }
```

Fields: Flix version, adapter family, status (`verified`, `rejected`, `unrecorded`), capabilities.
`build.mill`, `fetch-flix.sh`, the compatibility table, the CI loops and the registry derive from it.
Status values come from the existing documents: verified rows in the README table, rejected for
0.59.0 and 0.66.0 (`scripts/test-compiler-compatibility.sh` L76-93). `unrecorded` is proposed
(draft PRs #14, #15 and #17 concern unrecorded releases; contents not read for this document).

## 3. What stays in the plugins

In metrics: rules and thresholds (`RuleDefinitions.java`, `Thresholds.java`), smells and rankings
(`SourceMetrics.java`, `Rankings.java`), `Metrics`, `DatalogGraph`, `DocumentationMetrics`, baseline
comparison (`Baseline.java`), policy file (`MetricsConfig.java`), filters (`PresentationFilter.java`),
report formats text, json, md, sarif (`Formats.java`, `Metrics.java`), `Wire` payload codec,
`Initializer`, option parsing, and `docs/metrics-report.schema.json`.

In mutate: operators, the Datalog mutation engine, reports (`projects/flixw-mutate/docs/design/DESIGN.md`
L118-128 states the same rule: rules, operators and report formats stay in the plugins).

Rule for the boundary: nothing under `ca.uwaterloo.flix` is referenced outside `compiler`
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
capability and family, so each class has its own bytecode-derived contract. This is a proposal;
section 8 lists it as a question.

## 5. Three version levels and the report header

| Level | Owner of the number | Form | Where it lives |
| --- | --- | --- | --- |
| Flix | Compiler release | Data (`versions.json`) | `compiler` |
| SDK | `flixw-compiler-sdk` | Semver, embedded in the plugin jar; additive evolution, removal only in a major version | `compiler` and `kit` released together; `CompilerModel.SDK_VERSION` (L50) is declared and unused today |
| Plugin | Each plugin | Free | `Implementation-Version` of the jar, read by `Main.version()` (L277-280) |

Report header today (`Provenance.java` L18-19, `json()` L21-28): `commit`, `dirty`,
`analyzerVersion`, `measuredAt`, `compilerArtifact` (file name only, `Provenance.java` L35),
`inputDigest`. The schema accepts any object (`docs/metrics-report.schema.json` L21), so fields are
additive at the schema level; `Metrics.schemaVersion()` is 35 (`Metrics.java` L78-79) and each
additive change has raised it (`docs/COMPILER-SDK.md` L203-210, L240-241).

Proposed additional fields, all populated by `kit` and `compiler`:

| Field | Meaning | Source |
| --- | --- | --- |
| `sdkVersion` | Embedded SDK semver | SDK build |
| `flixVersion` | Version of the pinned compiler | UNKNOWN: no file in this repository reads it. `compilerArtifact` is only the jar file name. flixw's `lock.toml` holds it (DESIGN.md L74) but was not read for this document |
| `compilerDigest` | SHA-256 of the pinned jar | Computed already for the cache key (`ResultCache.java` L75), not reported |
| `adapter` | Chosen adapter class and family | `Adapters.resolve()` |
| `capability` | Capability the report was produced with | Plugin |
| `skippedAdapters` | List of `{adapter, reason}` for each adapter that was tried and rejected, with the missing references | Not available today: `Adapters.resolve()` discards the result at L41-42 (`continue`) and swallows the exceptions at L46-50 |

`Main.java` L108-109 reports only the list of known adapters when none links. With
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
   `plugin/test/fixtures/semantic-validation` (`scripts/test-compiler-compatibility.sh` L166-168),
   so a fixture is chosen per family.
3. Cross-version parity within a family, as in the compatibility table (columns "Semantic fixture"
   and "Corpus").
4. Failure mode: an unlinkable jar produces the sentence and exit code `2`, not a stack trace
   (`Main.java` L130-137). The case where no adapter links is a separate sentence (`Main.java` L107-109, L138-141).

The dependency on metrics classes is in the adapter code, not in the tests: all seven adapters
import `dev.flixw.metrics.DatalogGraph` (`Flix0761Adapter.scala` L5), and every adapter module
compiles against `plugin.compile().classes` (`build.mill` L24). It has to move or be inverted
before the suite is independent (question 2).

## 7. Migration order

Rule: regression first. Before each step the existing tests are green on the parent; after it they
are green without edits (`make test`, `AGENTS.md` "Testing Guidelines"). A test change in the same
PR as a move is not allowed except for package and import renames that a reviewer can verify
mechanically.

| Step | Issue | Content | Gate |
| --- | --- | --- | --- |
| 0 | #20 | This document | Owner decision |
| 0a | owner | Repository `wstein/flixw-compiler-sdk` exists (blocker of #20) | Owner |
| 1 | #21 | Extract `kit` (section 1) without the ABI driver and `StableInputs`, which depend on `compiler` or plugin types (direction and couplings below). Metrics consumes `kit` as an embedded, pinned release asset | Existing tests unchanged. Reports on the calibration corpus equal before and after, compared as `jq -S 'del(.provenance)'` (the pattern of `scripts/test-compiler-compatibility.sh` L205; `provenance.measuredAt` differs on every run, so byte equality is not a gate) |
| 2 | #22 | Move adapters, `AdapterAbi`, `Adapters`, `CompilerModel`, the ABI driver, `fetch-flix.sh`, compatibility documents and verification CI into `compiler`; introduce `versions.json` | Existing adapter tests green and unedited apart from package renames. The conformance suite (section 6) runs in the SDK repository for all seven families. `scripts/test-compiler-compatibility.sh` is split (below); both halves are green against the same version list |
| 3 | #23 | Capability-based selection and the new header fields | New tests first (failing against step 2), cache key change documented, schema version raised |
| 4 | none | `ProgramLoader`, `TestRunner` once mutate needs them; `SolverHook` after the spike. Also after the spike, from DESIGN.md L97-101 and L124: a run-time smoke test in the SDK that metrics can reuse, declared-member lookup for hook targets (`getDeclaredMethods`, paired with the smoke test), and a CI matrix over the version data that runs the smoke test | Not before the spike |

Dependency direction (design proposal): `compiler` depends on `kit`; `kit` knows neither `compiler`
nor Flix. Hence the ABI driver is not in step 1, and `StableInputs` and the `ModelFailure` handling follow question 11.
The alternative, a `kit` that defines the contract and failure types and `compiler` that fills them,
is question 11.

`scripts/test-compiler-compatibility.sh` needs `dist/plugin.jar` (L73) and `PluginIntegrationTest`
(L161-163), so it cannot run in the SDK repository as it is. Proposal for step 2: the ABI-gate part
(L76-150, official jars through the `capabilities` command, which also runs `dist/plugin.jar`, L73)
moves to the SDK repository and runs there through the conformance suite or a fixture plugin; the packaged-integration part (L161-179) stays in
`flixw-metrics` and runs against the embedded SDK. Whether the script splits at that line is
UNKNOWN. Question 8 stays open until then.

Publication of the SDK release asset and the SHA-256 pin are owner operations (`AGENTS.md`, owner
controls artifact publication). Issue #24 (digest check of the cached compiler jar) touches
`scripts/test-compiler-compatibility.sh` and `scripts/fetch-flix.sh`; it lands before step 2 or
moves with it.

## 8. Open questions

1. Package names: `dev.flixw.metrics.sdk` and `dev.flixw.metrics.adapter` cannot stay once the code
   belongs to another repository. Rename to an SDK package; effect on the `Flix0NNNAdapter` class
   names in `Adapters.KNOWN` is UNKNOWN. On the cache key the effect is derivable: the plugin artifact tree is hashed (`ResultCache.java` L74), so a rename changes the hash and every existing entry misses once.
2. Adapters import `dev.flixw.metrics.DatalogGraph` (metrics class). Move it into `compiler` or
   keep Datalog analysis behind the model.
3. `CompilerModel` records are metrics-shaped. Keep one `Metrics` capability model, or split the
   model per capability. Mutate's needs are UNKNOWN until the spike.
4. One class per capability and family (section 4) against one class implementing several
   interfaces. The first keeps per-capability contracts exact.
5. Format of `versions.json`, and whether the digest of each jar belongs to it.
6. Cache key: whether SDK version and adapter family join the key explicitly. The plugin artifact
   tree is already hashed (`ResultCache.java` L74), which covers embedded SDK bytes, so an explicit
   field adds readability, not invalidation.
7. Where `flixVersion` comes from (section 5).
8. How the SDK repository runs packaged integration without a plugin (fixture plugin, or the
   conformance suite alone).
9. Whether flixw ABI v2 should supply the SDK later: owner decision, only with a third plugin
   (chartroom note).
10. Shell-level exit codes: `kit` owns `2`; whether mutate's "compiler incompatible" also uses `2`
    (DESIGN.md L78) is consistent with `Main.java` L87-94 but is not tested.
11. Dependency direction between `kit` and `compiler` (section 7): `compiler` depends on `kit` with
    the ABI driver in `compiler` (proposal), or `kit` defines the contract and failure types. Also
    whether `StableInputs` stays in the plugin or takes the source lister as a parameter, and how
    the other couplings listed after the table in section 1 are cut.

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
