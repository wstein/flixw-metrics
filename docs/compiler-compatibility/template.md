# Flix VERSION compatibility investigation

## Inputs

- Release:
- Release commit:
- Compiler artifact SHA-256:
- Compared with:
- Analyzer commit:
- Java and platform:

## Upstream changes reviewed

List changed compiler types, members, representations, and dependencies that intersect or could
affect the adapter. Link the release comparison and the most relevant commits.

## Adapter impact

Record which bound APIs changed, which remained stable, and whether the release reuses an existing
linkage family or requires a new adapter. Include semantic risks that a binary ABI check cannot see.

## Verification

| Check | Command or method | Result |
| --- | --- | --- |
| Artifact digest |  |  |
| Adapter source build |  |  |
| Bytecode-derived ABI gate |  |  |
| Semantic fixture |  |  |
| Packaged integration |  |  |
| Corpus calibration |  |  |
| Performance contract |  |  |
| Full test suite |  |  |

Record unexpected failures explicitly, including whether they belong to the analyzer, compiler,
or an external calibration target. Never remove a failing target without preserving its pin and
last known evidence.

## Decision and compatibility impact

- Supported range and adapter:
- Required implementation changes:
- Report schema:
- Wire format and cache:
- SDK and capability JSON:
- CLI:
- Class-path or dependency implications:
- Follow-up work:
