<!-- markdownlint-disable MD013 -->

# Real-project calibration, 2026-09-12

This is a dogfood run, not a claim that five repositories represent all Flix code. Its purpose is
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

The analyzer baseline was commit `17de59e`; the final policy includes the calibrated threshold in
`5f44ff9`. All runs used OpenJDK 21.0.12.1 on Darwin arm64 and the pinned Flix 0.75.3 artifact with
SHA-256 `bf123cdb6494d6e0cbff6399bf185314d332bbe97bfd776e4abc03a5d39dd954`.

| Target | Source commit | Declared Flix | Files | Definitions | Lines |
| --- | --- | ---: | ---: | ---: | ---: |
| [`flix/flix` Prelude](https://github.com/flix/flix/blob/1c26436689127913f7b447869712df0f04507996/main/src/library/Prelude.flix) | `1c264366` | 0.75.3 | 1 | 21 | 229 |
| [`stephentetley/flix-basicdb`](https://github.com/stephentetley/flix-basicdb/tree/c83d571538ac4a16eed1ba8e1c445805897493e9) | `c83d5715` | 0.75.3 | 10 | 44 | 1,612 |
| [`stephentetley/flix-parsec`](https://github.com/stephentetley/flix-parsec/tree/d549c99ab6034c859ae1b27b338f559e5c61d911) | `d549c99a` | 0.75.0 | 20 | 258 | 2,330 |
| [`KengoTODA/flix-semver2`](https://github.com/KengoTODA/flix-semver2/tree/4473950e945e61717000a87d83b94320d0e7e78b) | `4473950e` | 0.73.0 | 2 | 36 | 299 |
| [`mlutze/flix-json`](https://github.com/mlutze/flix-json/tree/ac9d50c40d1f3fcf5a5317735ab58116e9eaf2ee) | `ac9d50c4` | 0.49.0 | 15 | 113 | 1,478 |

The older projects are compatibility probes, not evidence that the adapter supports every compiler
between 0.49 and 0.75. They compile successfully with the pinned 0.75.3 compiler, so the measured
AST is still the supported one.

Normal projects were cloned at the commits above, packaged with `scripts/package.sh`, and measured
with a fresh plugin cache. Prelude is compiler-embedded and is intentionally excluded from normal
project reports. It was measured through the same adapter and AST walker with:

```sh
sh scripts/calibrate-prelude.sh /path/to/flix-checkout
```

The runner takes semantic measurements from the compiler's exact virtual `Prelude.flix` and line
measurements from the pinned checkout. This avoids a renamed or namespaced copy, which changes the
meaning of Prelude's compiler-intrinsic declarations.

## Results after calibration

| Target | Findings | Finding counts by rule |
| --- | ---: | --- |
| Prelude | 5 | crammed-line 4; line-too-long 1 |
| flix-basicdb | 87 | definition-too-long 6; line-too-long 47; undocumented-public 34 |
| flix-parsec | 245 | crammed-line 21; deeply-nested 1; line-too-long 50; too-many-parameters 1; undocumented-public 170; wide-coupling 2 |
| flix-semver2 | 7 | dense 1; undocumented-public 6 |
| flix-json | 60 | crammed-line 8; dense 2; line-too-long 49; undocumented-public 1 |
| **Total** | **404** | 472 definitions and 5,948 lines |

The volume is dominated by two intentionally note-level policies: 211 missing public doc comments
and 147 lines over 100 UTF-16 code units. Of the long lines, 51 are under `test/`; of the 33
remaining crammed-line observations, five are under `test/`. These are real measurements but often
formatting or documentation backlog, so they should not be used as a warning gate by default.

The 13 structural observations were much more concentrated:

- Six long definitions are JDBC effect handlers spanning 71–203 lines. Their repeated operation
  cases are understandable, but the locations are credible extraction/refactoring candidates.
- `FlixParsec.Internal.ParseError.textPosHelper` has seven parameters and five nested branch
  levels. Both independent rules point to the same difficult recursive helper.
- `FlixParsec.Literal` and `FlixParsec.Token` directly call 17 and 14 modules respectively. These
  are plausible facade modules; the finding is useful architectural context, not proof they should
  be split.
- Three dense parsing helpers barely or materially exceed 1.0 complexity per code line. They merit
  inspection; no threshold adjustment is supported by only three observations.

Selected distribution points show why thresholds were not tuned merely to manufacture findings:

| Target | Definition lines p95/max | Parameters p95/max | Nesting p95/max | Tokens/line p95/max | Fan-out p95/max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Prelude | 11/12 | 2/4 | 1/1 | 41/53 | 0/0 |
| flix-basicdb | 106/203 | 2/4 | 2/2 | 32/35 | 7/8 |
| flix-parsec | 11/34 | 4/7 | 1/5 | 37/48 | 14/17 |
| flix-semver2 | 9/11 | 2/3 | 2/3 | 23/26 | 2/6 |
| flix-json | 20/42 | 2/3 | 2/4 | 36/43 | 9/10 |

No project exercised a wide return or Datalog rule/fact. Those implementations remain covered by
focused tests, but this corpus does not validate their defaults.

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

## Decisions and rated next actions

Ratings are confidence that the action improves signal, from 1 (speculative) to 5 (well supported).

| Rating | Decision or suggestion | Rationale |
| ---: | --- | --- |
| **5/5** | Raise `crammed-line` from 30 to 35 tokens. **Done.** | The old boundary produced 76 notes, including idiomatic Prelude/combinator signatures. The new boundary produces 33 while retaining the 40–53-token outliers. |
| **5/5** | Keep the structural defaults. | The 13 hits are sparse, independently interpretable, and point to concrete code or module boundaries. |
| **5/5** | Gate `warning` or higher in CI; review `note` findings as backlog. | Documentation and formatting account for 358 of 404 observations. Treating all findings as equivalent would hide the structural signal. |
| **4/5** | Use project configuration for established line-length and documentation conventions. | A universal increase would erase useful notes for compact projects; the existing per-rule limits and suppressions preserve local policy. |
| **4/5** | Add at least one Datalog-heavy and one record-heavy project before changing `wide-return` or Datalog-related behavior. | This corpus contains no empirical observations for either family. |
| **3/5** | Repeat cold timings in CI before setting a performance budget. | The cache-hit result is strong, but four local single samples are not a stable cross-machine benchmark. |

Re-run this calibration when the compiler adapter changes, when a default threshold changes, or
when the corpus gains a materially different Flix style. Compare distributions and reviewed
observations, not only the total finding count.
