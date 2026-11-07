# Compiler compatibility investigations

The adapter binds to compiler internals that do not promise ABI or semantic stability. This
directory records the evidence behind each supported Flix release. A passing linkage gate is
necessary but not sufficient: every investigation also compiles the adapter against the release,
runs semantic and packaged-process regressions, and compares the calibration corpus.

| Flix | Adapter family | Source build | ABI gate | Semantic fixture | Integration | Corpus | Record |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 0.60.0 | `Flix0600Adapter` | yes | yes | yes | yes | not rerun | [investigation](flix-0.60.0.md) |
| 0.61.0 | `Flix0610Adapter` | yes | yes | yes | yes | not rerun | [investigation](flix-0.61.0.md) |
| 0.65.0 | `Flix0610Adapter` | yes | yes | yes | yes | not rerun | [family endpoint](flix-0.61.0.md) |
| 0.66.1 | `Flix0661Adapter` | yes | yes | yes | yes | not rerun | [investigation](flix-0.66.1.md) |
| 0.67.1 | `Flix0661Adapter` | yes | yes | yes | yes | not rerun | [investigation](flix-0.67.1.md) |
| 0.67.2 | `Flix0672Adapter` | yes | yes | yes | yes | not rerun | [investigation](flix-0.67.2.md) |
| 0.68.0 | `Flix0680Adapter` | yes | yes | yes | yes | not rerun | [investigation](flix-0.68.0.md) |
| 0.75.2 | `Flix0680Adapter` | yes | yes | yes | yes | not rerun | [investigation](flix-0.75.2.md) |
| 0.75.3 | `Flix0753Adapter` | yes | yes | yes | yes | baseline | Newer family boundary; packaged integration locked in CI |
| 0.76.0 | `Flix0753Adapter` | yes | yes | yes | yes | 9 active targets; 2 upstream-incompatible | [investigation](flix-0.76.0.md) |
| 0.76.1 | `Flix0761Adapter` | yes | yes | yes | yes | exact parity on 9 active targets | [investigation](flix-0.76.1.md) |
| 0.77.0 | `Flix0761Adapter` | yes | yes | yes | yes | exact parity on 9 active targets | [investigation](flix-0.77.0.md) |
| 0.78.0 | `Flix0761Adapter` | no | yes | yes | yes | not rerun | [investigation](flix-0.78.0.md) |
| 0.79.0 | `Flix0761Adapter` | no | yes | yes | yes | not rerun | [investigation](flix-0.79.0.md) |

Declaration positions: from 0.79.0 (not 0.78.0), the compiler starts a documented definition or
effect at its `def`/`eff` line or annotation instead of its doc comment, and ends a definition at
its last token instead of its last trailing comment. `Flix0761Adapter` takes the earlier start
from `Doc.loc` and the comments that directly follow the last token from the lexer for every
release, so `line`, `lines` and the findings that depend on them match the 0.76.1 through 0.78.0
values on 0.79.0 for the projects checked ([record](flix-0.79.0.md)).

## What the test suite runs per release

The table above records the investigation behind each release. This one records what the current
suite executes automatically, so a gap is visible. "Fixture test" is the `Flix*AdapterTest`
semantic fixture, which runs against one jar per family, not against every release of the family.

| Flix | Adapter family | `test-compiler-compatibility.sh` (capabilities, packaged integration) | Fixture test (jar it runs against) | Span comparison (`DefinitionSpanTest`, report against 0.77.0) | `test-flix-sample.sh` | Calibration |
| --- | --- | --- | --- | --- | --- | --- |
| 0.60.0 | `Flix0600Adapter` | yes | `Flix0600AdapterTest` (0.60.0) | no | no | no |
| 0.61.0 | `Flix0610Adapter` | yes | `Flix0610AdapterTest` (0.61.0) | no | no | no |
| 0.65.0 | `Flix0610Adapter` | yes | no | no | no | no |
| 0.66.1 | `Flix0661Adapter` | yes | `Flix0661AdapterTest` (0.66.1) | no | no | no |
| 0.67.1 | `Flix0661Adapter` | yes | no | no | no | no |
| 0.67.2 | `Flix0672Adapter` | yes | `Flix0672AdapterTest` (0.67.2) | no | no | no |
| 0.68.0 | `Flix0680Adapter` | yes | `Flix0680AdapterTest` (0.68.0) | no | no | no |
| 0.75.2 | `Flix0680Adapter` | yes | no | no | no | no |
| 0.75.3 | `Flix0753Adapter` | yes | no | no | no | no |
| 0.76.0 | `Flix0753Adapter` | yes | `Flix0753AdapterTest` (0.76.0) | no | no | no |
| 0.76.1 | `Flix0761Adapter` | yes | no | yes | no | no |
| 0.77.0 | `Flix0761Adapter` | yes | `Flix0761AdapterTest` and `PluginIntegrationTest` (the 0.77.0 pin in `plugin/lib/flix.jar`) | yes | no | yes, the only calibration compiler |
| 0.78.0 | `Flix0761Adapter` | yes | no | yes | yes | no |
| 0.79.0 | `Flix0761Adapter` | yes | no | yes | yes | no |

The same script also requires 0.59.0 and 0.66.0 to be refused (not in the table: they are not
supported). `test-flix-sample.sh` analyzes `plugin/test/fixtures/release-sample` (three files, four
modules, one `eff` with a handler, one `@Test`, one documented definition) with the
real 0.78.0 and 0.79.0 jars, digest-checked against the `fetch` lines of the compatibility script,
and compares `files`, `modules`, `definitions`, `effects`, `tests`, `cognitive`,
`effectPolymorphicDefinitions` and the smell rules with `plugin/test/golden/release-sample-*.json`.
Both releases currently give the totals in the golden files (3 files, 4 modules, 7 definitions, 1
effect, 1 test, cognitive 4, 1 effect-polymorphic definition, 4 smells). The suite does not run 0.77.0
on this project: its `flix.toml` is written for Flix 0.78 and later.

The span comparison in `test-compiler-compatibility.sh` runs `DefinitionSpanTest` (a project with
documented definitions, trailing comments and effects) on 0.76.1, 0.77.0, 0.78.0 and 0.79.0 and
requires the packaged report of each, without provenance, to equal the 0.77.0 report. It also
requires 12 `definition-too-long` findings on 0.79.0 and that a baseline taken on 0.77.0 holds on
0.79.0 with nothing new, resolved or worsened. `test-flix-sample.sh` additionally pins the
`Counter.silence` position per release.

Not established by the suite: whether the semantic fixture, corpus metrics or any threshold
distribution on releases without a "yes" in the fixture or calibration column match the pin. For
definition and effect positions and the findings that depend on them, the span comparison covers
0.76.1 through 0.79.0 only; older families are not compared. The
"473 checks" in the 0.78.0 and 0.79.0 records come from a manual run with `plugin/lib/flix.jar`
swapped; that run is not reproduced by the suite and was not repeated for this table. A Flix
release newer than 0.79.0 is loaded if it links, with one stderr warning that it has no checkpoint
(see [COMPILER-SDK.md](../COMPILER-SDK.md#a-release-newer-than-any-recorded-one)).

The adapter-family suffix names its oldest compatible release. It never encodes an upper bound:
the upper end is evidence that grows release by release, not part of the JVM package name. The
plugin itself retains an independent SemVer because its reporting behavior evolves separately.

`scripts/test-compiler-compatibility.sh` enforces the current lower boundary with official release
artifacts: 0.59.0 must fail the AST gate and 0.66.0 must fail compiler-runtime inspection, while
0.60.0, 0.61.0, 0.65.0, 0.66.1, 0.67.1, 0.67.2, 0.68.0, 0.75.2, 0.75.3, 0.76.0, 0.76.1, 0.77.0, 0.78.0, and 0.79.0 must pass packaged
integration. Intermediate releases may satisfy the structural gate, but remain unverified
until they receive their own row and investigation.

For a new release, copy [the template](template.md), complete every applicable check, add the
release to `plugin/resources/dev/flixw/metrics/verified-flix-versions.txt` together with its `fetch`
line and integration checkpoint in `scripts/test-compiler-compatibility.sh` (the script fails if
they disagree), update this matrix, and link the record from the compiler-repin pull request. Keep measurement distributions
and threshold decisions in [`docs/CALIBRATION.md`](../CALIBRATION.md); this directory answers the
different question of whether a compiler release can be loaded and interpreted correctly.
