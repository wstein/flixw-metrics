# Compiler compatibility investigations

The adapter binds to compiler internals that do not promise ABI or semantic stability. This
directory records the evidence behind each supported Flix release. A passing linkage gate is
necessary but not sufficient: every investigation also compiles the adapter against the release,
runs semantic and packaged-process regressions, and compares the calibration corpus.

| Flix | Adapter family | Source build | ABI gate | Semantic fixture | Integration | Corpus | Record |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 0.67.2 | `Flix0672Adapter` | yes | yes | yes | yes | not rerun | [investigation](flix-0.67.2.md) |
| 0.68.0 | `Flix0680Adapter` | yes | yes | yes | yes | not rerun | [investigation](flix-0.68.0.md) |
| 0.75.2 | `Flix0680Adapter` | yes | yes | yes | yes | not rerun | [investigation](flix-0.75.2.md) |
| 0.75.3 | `Flix0753Adapter` | yes | yes | yes | yes | baseline | Newer family boundary; packaged integration locked in CI |
| 0.76.0 | `Flix0753Adapter` | yes | yes | yes | yes | 9 active targets; 2 upstream-incompatible | [investigation](flix-0.76.0.md) |

The adapter-family suffix names its oldest compatible release. It never encodes an upper bound:
the upper end is evidence that grows release by release, not part of the JVM package name. The
plugin itself retains an independent SemVer because its reporting behavior evolves separately.

`scripts/test-compiler-compatibility.sh` enforces the current lower boundary with official release
artifacts: 0.67.1 must fail the derived ABI gate, while 0.67.2, 0.68.0, 0.75.2, and 0.75.3 must pass
packaged integration. Intermediate releases may satisfy the structural gate, but remain unverified
until they receive their own row and investigation.

For a new release, copy [the template](template.md), complete every applicable check, update this
matrix, and link the record from the compiler-repin pull request. Keep measurement distributions
and threshold decisions in [`docs/CALIBRATION.md`](../CALIBRATION.md); this directory answers the
different question of whether a compiler release can be loaded and interpreted correctly.
