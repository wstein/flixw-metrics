# Compiler compatibility investigations

The adapter binds to compiler internals that do not promise ABI or semantic stability. This
directory records the evidence behind each supported Flix release. A passing linkage gate is
necessary but not sufficient: every investigation also compiles the adapter against the release,
runs semantic and packaged-process regressions, and compares the calibration corpus.

| Flix | Adapter family | Source build | ABI gate | Semantic fixture | Integration | Corpus | Record |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 0.75.3 | `Flix075Adapter` | yes | yes | yes | yes | baseline | Original implementation |
| 0.76.0 | `Flix075Adapter` | yes | yes | yes | yes | 9 active targets; 2 upstream-incompatible | [investigation](flix-0.76.0.md) |

For a new release, copy [the template](template.md), complete every applicable check, update this
matrix, and link the record from the compiler-repin pull request. Keep measurement distributions
and threshold decisions in [`docs/CALIBRATION.md`](../CALIBRATION.md); this directory answers the
different question of whether a compiler release can be loaded and interpreted correctly.
