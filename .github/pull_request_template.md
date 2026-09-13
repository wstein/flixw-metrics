## Summary

<!-- Explain the observable behavior change and why it is needed. -->

Closes #

## Evidence

<!--
For metric or threshold changes, include corpus distributions, counterexamples, and the
calibration output location. For compiler changes, link the compatibility investigation.
Delete this comment only after replacing it with evidence or "Not applicable" and a reason.
-->

## Compatibility impact

Describe each impact; do not leave a checked item unexplained when its format changed.

- [ ] Native report or JSON Schema
- [ ] Measurement wire format or cache
- [ ] Stable SDK
- [ ] Capability JSON
- [ ] CLI or exit status
- [ ] Packaged classes or manifest
- [ ] No compatibility surface changed

Details:

## Verification

List the exact commands run and their results.

```console
make format
make lint
make test
```

Additional checks:

## Adapter checklist

Complete this section for compiler-facing changes; otherwise mark it not applicable.

- [ ] Compiler types remain confined to a `flixNNNN` adapter module.
- [ ] The adapter compiles against the oldest verified release in its family.
- [ ] The bytecode-derived capability gate passes or rejects at the intended boundary.
- [ ] Semantic and packaged integration fixtures cover the changed compiler representation.
- [ ] `docs/compiler-compatibility/` records the release evidence and metric parity.
- [ ] Compiler jars and generated `out/` or `dist/` files are not committed.

## Checklist

- [ ] Commits are focused, atomic, and use Conventional Commit subjects.
- [ ] Tests fail before and pass after a behavioral fix where practical.
- [ ] Documentation and examples reflect the final behavior.
- [ ] Security implications and untrusted input boundaries were considered.
