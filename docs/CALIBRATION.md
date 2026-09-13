<!-- markdownlint-disable MD013 -->

# Real-project calibration, updated 2026-09-13

This is a dogfood run, not a claim that eight targets represent all Flix code. Its purpose is
to test whether the defaults produce useful review leads on code the analyzer was not written
against, to expose false positives, and to put future threshold changes against a recorded
baseline.

The interpretation follows three useful constraints from the background research:

- [Kiuwan's overview](https://www.kiuwan.com/blog/code-quality-metrics/) treats complexity and
  rule violations as signals for prioritizing review and tracking change, not as substitutes for
  review.
- [Stan](https://github.com/kowainik/stan) uses compiler-produced AST information, stable
  observation identities, actionable advice, and configurable inspections. That is the closest
  design analogue for this compiler-backed analyzer.
- The [software metric overview](https://en.wikipedia.org/wiki/Software_metric) emphasizes that a
  quantitative property is useful only with a defined measurement method. Consequently, every
  row below pins source and compiler inputs instead of comparing floating branch heads.

## Corpus and method

The original analyzer baseline was commit `17de59e`; the final policy includes the calibrated
threshold in `5f44ff9`. The Flix 0.76 compatibility rerun used analyzer commit `8e47297` on OpenJDK
21.0.12.1, Darwin arm64, and the pinned Flix 0.76.0 artifact with SHA-256
`d8d9a3870e199c03ed6364ea9430f56f67bfd38c332c411628a6a7cb88b2b0b4`.

| Target | Source commit | Declared Flix | Files | Definitions | Lines |
| --- | --- | ---: | ---: | ---: | ---: |
| [`flix/flix` Prelude](https://github.com/flix/flix/blob/f2d4678c20bff1242f4cad5e23144db91027b762/main/src/library/Prelude.flix) | `f2d4678c` | 0.76.0 | 1 | 21 | 229 |
| [`KengoTODA/flix-semver2`](https://github.com/KengoTODA/flix-semver2/tree/4473950e945e61717000a87d83b94320d0e7e78b) | `4473950e` | 0.73.0 | 2 | 36 | 299 |
| [`mlutze/flix-json`](https://github.com/mlutze/flix-json/tree/ac9d50c40d1f3fcf5a5317735ab58116e9eaf2ee) | `ac9d50c4` | 0.49.0 | 15 | 113 | 1,478 |
| [`ababup1192/flix_game_engine`](https://github.com/ababup1192/flix_game_engine/tree/a44ad70e479082bc506b2d741efe95dc7531e3b9) | `a44ad70e` | 0.75.1 | 176 | 2,396 | 33,171 |
| [`Simmypeet/qual-effect-system`](https://github.com/Simmypeet/qual-effect-system/tree/5632eaf6f2a0c4d65cdf75ce3d62ea032a61d3be) | `5632eaf6` | 0.75.1 | 19 | 36 | 889 |
| [`flix/flix` Datalog examples](https://github.com/flix/flix/tree/f2d4678c20bff1242f4cad5e23144db91027b762/examples/datalog) | `f2d4678c` | 0.76.0 | 4 | 48 | 971 |

The older projects are source-compatibility probes, not evidence that the adapter supports their
declared compiler versions. The four active projects compile successfully with the pinned 0.76.0
compiler, so the measured AST is the supported one.

Two original targets fail before measurement under Flix 0.76.0 and are retained, with their pins
and last 0.75.3 results, in `calibration/corpus.json` under `incompatibleProjects`:

| Target | Source commit | 0.76.0 failure |
| --- | --- | --- |
| [`flix-basicdb`](https://github.com/stephentetley/flix-basicdb/tree/c83d571538ac4a16eed1ba8e1c445805897493e9) | `c83d5715` | Its `flix-time` 0.23.0 dependency uses covariant Java returns that the descriptor-based resolver does not unify. |
| [`flix-parsec`](https://github.com/stephentetley/flix-parsec/tree/d549c99ab6034c859ae1b27b338f559e5c61d911) | `d549c99a` | Its pre-public-module source fails the new module accessibility checks. |

Normal projects were cloned at the commits above, packaged with `scripts/package.sh`, and measured
with a fresh plugin cache. Prelude is compiler-embedded and is intentionally excluded from normal
project reports. It was measured through the same adapter and AST walker with:

```sh
sh scripts/calibrate-prelude.sh /path/to/flix-checkout
```

The runner takes semantic measurements from the compiler's exact virtual `Prelude.flix` and line
measurements from the pinned checkout. This avoids a renamed or namespaced copy, which changes the
meaning of Prelude's compiler-intrinsic declarations.

The Datalog row is four standalone programs (`compiler-puzzle`, `dependency-resolution`,
`ford-fulkerson`, and `railroad-network`). Each source was copied without modification into an
otherwise empty project's `src/` directory and analyzed separately; totals in the table are their
sum. They complement `qual-effect-system`: the project exercises derived rules in normal library
code, while the examples also carry literal facts.

## Reproducing the pinned corpus

The machine-readable source pins and expected per-target counts live in
[`calibration/corpus.json`](../calibration/corpus.json). Run the complete check into a new or empty
directory:

```sh
sh scripts/calibrate-corpus.sh /tmp/flixw-calibration-results
```

The runner builds the current analyzer, fetches every repository directly at its full commit SHA,
measures Prelude, the four active projects, and the four Datalog examples, then compares stable summaries
with the manifest. It exits 1 with a per-target diff when measurements change and exits 2 for an
invalid manifest or unavailable input. Full native JSON reports and `summary.json` remain in the
output directory for review. Every normal project and Datalog target is then measured again from
the warm cache with its full report as `--baseline` and `--view changes`; those compact reports are
retained under `changes/`, and any non-empty finding or measurement delta fails the run. Prelude is
the sole exception because its dedicated standard-library harness is not a normal Flix project.

For an offline repeat, set `CALIBRATION_SOURCE_ROOT` to a directory containing checkouts named
`flix`, `flix-semver2`, `flix-json`, `flix-game-engine`, and `qual-effect-system`. Their `HEAD`s
must still equal the manifest's full SHAs; the runner refuses a nearby revision. Incompatible
projects are recorded but deliberately not cloned or measured.

The `calibration` GitHub Actions workflow runs this every Monday at 05:23 UTC and can also be
started manually. Its read-only job retains the reports for 30 days even when drift fails the run.
When drift is intentional, inspect those reports and update the manifest expectations in the same
change as the analyzer or policy adjustment. Moving a source pin is a separate corpus decision,
not a way to make unexpected counts pass.

## Results after calibration

| Target | Findings | Finding counts by rule |
| --- | ---: | --- |
| Prelude | 5 | crammed-line 4; line-too-long 1 |
| flix-semver2 | 7 | dense 1; undocumented-public 6 |
| flix-json | 60 | crammed-line 8; dense 2; line-too-long 49; undocumented-public 1 |
| flix-game-engine | 1,200 | crammed-line 109; deeply-nested 15; definition-too-long 23; dense 72; line-too-long 725; noisy-flixdoc-parameters 28; too-many-parameters 89; undocumented-public 122; wide-coupling 16; wide-return 1 |
| qual-effect-system | 27 | deeply-nested 1; definition-too-long 1; line-too-long 4; undocumented-public 21 |
| Flix Datalog examples | 50 | crammed-line 17; line-too-long 33 |
| **Total** | **1,349** | 2,650 definitions and 37,037 lines |

Every active target retained its exact 0.75.3 summary and finding counts. The detailed distribution
discussion below is preserved from the original threshold calibration and includes the two now
incompatible projects where it names them; those observations remain historical threshold evidence,
not claims that Flix 0.76.0 can compile those revisions.

The expanded volume remains dominated by note-level policy: 909 lines over 100 UTF-16 code units,
354 missing public doc comments, and 159 crammed lines. The game engine intentionally embeds shader
source and other large data and contains a 4,109-unit line, demonstrating why line findings need
scoped suppression for generated or embedded content rather than a universal higher limit.

Across 1,484 public non-nullary signatures, the generated FlixDoc formal-parameter span has a
median of 33 Unicode characters, p95 of 105, p99 of 155, and maximum of 287. A provisional limit
of 100 produced 88 notes; 140 retains 29 stronger outliers—28 in the game engine and one in
flix-parsec. Seven of the over-100 signatures have only one formal parameter, including rendered
spans of 146–194 characters, so this measure observes type/API load that parameter count cannot.

The strict redundant-parameter prose recognizer produced no findings in the pinned corpus. That is
useful negative evidence, not threshold evidence: Flix documentation has no structured `@param`
field and the sampled projects do not conventionally mirror formals as Markdown lists. A semantic
prose classifier would invent false confidence, so the shipped note recognizes only exact formal
labels with boilerplate-only descriptions and requires at least two entries. The packaged semantic
fixture supplies the positive end-to-end case.

The original corpus's 13 structural observations remain credible. The larger game engine adds
enough variety to exercise the upper tail rather than only its threshold:

- Six long definitions are JDBC effect handlers spanning 71–203 lines. Their repeated operation
  cases are understandable, but the locations are credible extraction/refactoring candidates.
- `FlixParsec.Internal.ParseError.textPosHelper` has seven parameters and five nested branch
  levels. Both independent rules point to the same difficult recursive helper.
- `FlixParsec.Literal` and `FlixParsec.Token` directly call 17 and 14 modules respectively. These
  are plausible facade modules; the finding is useful architectural context, not proof they should
  be split.
- Three dense parsing helpers barely or materially exceed 1.0 complexity per code line. They merit
  inspection; no threshold adjustment is supported by only three observations.
- The engine's p95 definition still spans only 26 lines, but its 23 long-definition findings include
  a 1,118-line LWJGL handler. Its parameter, nesting, and coupling findings likewise identify a
  small high-complexity rendering core rather than shifting the whole distribution.
- Return width now has 119 definitions wider than one part across the two added projects. Only
  `UiDoc.parseOwnFields`, an anonymous record with eight top-level fields, exceeds the limit of five.
- `TileLayer.usedRect` exposed a false positive: it has two top-level record fields, each containing
  a two-field vector, but was flattened to width six. Commit `bb41851` fixes the metric to follow
  only the top-level record row, and a compiler-backed nested-record regression test locks that in.
- Datalog counts matched source inspection: `qual-effect-system` contributes seven derived rules;
  the four Flix examples contribute 25 rules and 28 facts. Commit `3bcae21` now exercises separate
  rule/fact counts through both the real compiler adapter and packaged JSON report.
- Declared effect width is strongly concentrated: among 2,952 definitions, 2,435 are pure, 459
  declare one effect, and only 13 declare four or more. The maximum is six, reached by three
  game-engine orchestration definitions whose retained effect names make the architectural role
  visible. This supports a ranking but not a finding.
- Seven definitions contain Datalog body dependencies. Their distinct-predicate breadths are
  1, 1, 2, 2, 2, 5, and 9. The upper two are the dependency-resolution and railroad examples,
  where inspecting the retained predicate names confirms that the ordering reflects the size of
  each logic program's relation surface rather than repeated atoms.
- The same seven dependency graphs have collapsed depths 2, 2, 3, 3, 3, 4, and 9. Five contain
  recursion: four have one self-recursive predicate, while railroad's `Circumvent` and `Connected`
  form the corpus's only two-predicate cycle. The names confirm recursion rather than treating a
  cyclic graph as infinitely deep.
- Across 1,721 public production definitions, compiler-rendered result types have a median width
  of 8 Unicode characters, p95 of 28, p99 of 45, and maximum of 96. None exceed 100. This supports
  locating complex generated results but provides no evidence for a result-width finding.
- Fan-in stays small in compact libraries and exposes architectural foundations in the larger
  projects: `FlixParsec.GenParser` is called from eight modules, while the game engine's root,
  `Vec2`, and `Num` modules have definition-call fan-in 116, 43, and 28. The root result also shows
  why this is impact context rather than automatic debt: unrelated top-level definitions share
  that synthetic module.

Selected distribution points show why thresholds were not tuned merely to manufacture findings:

| Target | Definition lines p95/max | Parameters p95/max | Nesting p95/max | Tokens/line p95/max | Fan-out p95/max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Prelude | 11/12 | 2/4 | 1/1 | 41/53 | 0/0 |
| flix-basicdb | 106/203 | 2/4 | 2/2 | 32/35 | 7/8 |
| flix-parsec | 11/34 | 4/7 | 1/5 | 37/48 | 14/17 |
| flix-semver2 | 9/11 | 2/3 | 2/3 | 23/26 | 2/6 |
| flix-json | 20/42 | 2/3 | 2/4 | 36/43 | 9/10 |
| flix-game-engine | 26/1,118 | 5/16 | 3/12 | 35/61 | 16/37 |
| qual-effect-system | 32/75 | 4/4 | 3/5 | 30/34 | 3/6 |

The expanded corpus therefore validates tuple and record widths from two through eight, plus both
Datalog rules and facts. Effect width, Datalog dependency breadth, collapsed depth, and recursive
participation remain measurements and rankings rather than findings: the observations establish
useful ordering, not arbitrary universal thresholds.

## Runtime and cache behavior

These are single sequential wall-clock samples after dependencies were already downloaded. A cold
run means an empty plugin measurement cache; it still performs Flix bootstrap and type checking.

| Target | Cold | Cache hit | Speed-up |
| --- | ---: | ---: | ---: |
| flix-basicdb | 7.74 s | 0.30 s | 25.8x |
| flix-parsec | 4.24 s | 0.31 s | 13.7x |
| flix-semver2 | 3.80 s | 0.25 s | 15.2x |
| flix-json | 4.27 s | 0.31 s | 13.8x |

The cache is doing materially useful work. Cold performance is dominated by compiler/project
bootstrap, especially dependency-heavy BasicDB; this sample does not justify adding another cache
layer.

### Repeated performance budget

The scheduled workflow separately runs the packaged semantic fixture five times cold and five
times warm. It gates medians rather than the slowest sample, while also gating the warm/cold ratio
so a uniformly fast or slow runner cannot hide a broken measurement cache.

| Measure | Local median | Hosted run 1 | Hosted run 2 | Budget |
| --- | ---: | ---: | ---: | ---: |
| Cold packaged run | 4,138 ms | 9,911 ms | 8,620 ms | at most 12,000 ms |
| Warm packaged run | 336 ms | 458 ms | 415 ms | at most 1,200 ms |
| Warm / cold | 8% | 4% | 4% | at most 25% |

The local measurements were recorded with Flix 0.76.0 on the same Darwin arm64 development machine
as the calibration above. The hosted measurements are two earlier independent Ubuntu runs.
The slower hosted cold median leaves 21% headroom under the ceiling, while both cache ratios are
well inside budget. That supports keeping the current ceilings: tightening the cold limit now would
mostly measure hosted-runner variance. CI retains each `performance.json` for 30 days so later
history can support a deliberate adjustment. Run the same contract locally with:

```sh
sh scripts/measure-performance.sh /tmp/flixw-performance.json
```

The budget and evaluator are separate from measurement, so their boundary behavior is covered by
the normal offline test suite without adding ten compiler startups to every pull request.

## Decisions and rated next actions

Ratings are confidence that the action improves signal, from 1 (speculative) to 5 (well supported).

| Rating | Decision or suggestion | Rationale |
| ---: | --- | --- |
| **5/5** | Raise `crammed-line` from 30 to 35 tokens. **Done.** | The old boundary produced 76 notes, including idiomatic Prelude/combinator signatures. The new boundary produces 33 while retaining the 40–53-token outliers. |
| **5/5** | Fix nested-record width to count top-level fields. **Done.** | The record-heavy project turned a synthetic concern into an observed false positive: two logical return parts were reported as six. |
| **5/5** | Keep the `wide-return` limit at five. | Among 2,432 definitions in the two added projects, 119 return multiple parts and only one exceeds five: an anonymous eight-field record that fits the rule's advice to name the shape. |
| **5/5** | Keep separate Datalog rule and fact totals. | Manual source counts match 32 rules and 28 facts across a real project and four official examples; end-to-end tests now preserve the distinction. |
| **5/5** | Keep the other structural defaults. | The much larger engine produces meaningful upper tails and its findings remain localized to complex rendering, interop, and orchestration code. |
| **5/5** | Gate `warning` or higher in CI; review `note` findings as backlog. | Documentation and formatting still dominate the expanded corpus. Treating all findings as equivalent would hide the structural signal. |
| **5/5** | Add a baseline-aware new-finding gate. **Done.** | Teams can adopt the warning gate without first paying all existing debt; schema and effective-policy checks prevent invalid comparisons. |
| **4/5** | Add a first-run initializer for policy and baseline adoption. **Done.** | One compiler-backed command creates a documented default policy, captures the compatible native baseline, prints the warning gate, and refuses to overwrite reviewed files. |
| **4/5** | Disclose the `dense` minimum-size condition in reports. **Done.** | Tiny definitions remain useful ranking context but are ineligible for findings below four code lines; human and machine formats now explain that distinction. |
| **4/5** | Measure generated FlixDoc formal-parameter load. **Done.** | The 140-character boundary selects 29 of 1,484 public signatures and catches long rendered types even when parameter count is small. |
| **4/5** | Rank declared effect-surface width. **Done.** | Only 13 of 2,952 definitions declare four or more effects; sorted effect names distinguish intentional orchestration boundaries from an unexplained count without asserting a smell. |
| **4/5** | Rank Datalog dependency breadth. **Done.** | Seven corpus definitions read relational predicates; their distinct breadth reaches nine, and the retained predicate names make repeated atoms, recursion, and broad logic programs auditable. |
| **4/5** | Measure Datalog dependency depth and recursion. **Done.** | Collapsing cycles produces finite depths from two through nine; retained cycle members identify four self-recursive programs and one mutual recursive two-predicate component without declaring recursion a smell. |
| **3/5** | Rank compiler-rendered FlixDoc result width. **Done.** | Public result types span 1–96 characters with p95 28; the ranking locates the upper tail without manufacturing a threshold unsupported by the corpus. |
| **3/5** | Rank high fan-in as scoped change impact. **Done.** | Resolved definition-call fan-in identifies shared foundations, while its explicit label and threshold-free treatment avoid calling stable, widely used modules defective. |
| **4/5** | Use project configuration for established line-length and documentation conventions. | A universal increase would erase useful notes for compact projects; the existing per-rule limits and suppressions preserve local policy. |
| **4/5** | Automate the pinned calibration corpus as a scheduled workflow. **Done.** | The weekly read-only job verifies full source SHAs and exact per-target results without adding network-heavy calibration to every pull request. |
| **4/5** | Add first-class exclusions for generated or embedded-data sources. **Done.** | Exclusions keep sources in the compiler while removing their line totals and located quality signals; every report discloses matched paths and reasons. |
| **3/5** | Establish performance budgets from repeated CI measurements. **Done.** | The scheduled job records five cold and five warm samples, enforcing conservative median and cache-speedup ceilings while retaining raw CI evidence for later tuning. |
| **3/5** | Mark individual ranking entries that cannot become findings. **Done.** | Native JSON and both human reports now identify structurally ineligible entries with a concrete reason, including tiny dense definitions and tests exempt from the length rule. |
| **3/5** | Detect mechanical formal-parameter prose mirrors. **Done, conservatively.** | Free Markdown cannot support a general semantic claim; exact list labels, a boilerplate-only vocabulary, and a two-entry floor provide a useful low-noise note without judging ordinary prose. |

Re-run this calibration when the compiler adapter changes, when a default threshold changes, or
when the corpus gains a materially different Flix style. Compare distributions and reviewed
observations, not only the total finding count.
