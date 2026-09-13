# metrics

Code metrics for [Flix](https://flix.dev) projects, counted by the compiler rather than by
reading the text. It installs as `metrics` and is a plugin for [flixw](https://github.com/wstein/flixw), measuring
your code with the exact compiler your project already pinned.

> **Experimental, third-party, unaffiliated** with the Flix project. A plugin is ordinary
> code running as you — see [Safety](#safety) before installing anything.

## Install

```console
./flixw plugin install metrics 0.1.10 \
  https://github.com/wstein/flixw-metrics/releases/download/v0.1.10/plugin.jar \
  --sha256 31fcd1586569bbf92048154849bec0a24ff2be5cb93916ed4231080884193d02
```

That digest is published here, not taken from the download, which is the point of passing
it: flixw re-checks those exact bytes on **every** run, not only at install.

Needs flixw 0.25.10 or newer, and a project with a pinned compiler.

It installs as `metrics`, and declares the verb `metrics` in its jar manifest — so
`./flixw metrics` works afterwards, in any project on the machine. `./flixw plugin metrics`
is the long form and always works; use it if your pinned compiler implements `metrics`
itself, since the compiler always wins.

Installing is enough to run it. A `[plugins.metrics]` entry in a project's `lock.toml` —
which `plugin install` writes — pins the version, which is what you want for CI and for a
colleague's clone.

## Run it

```console
$ ./flixw metrics
files: 3
modules: 2
definitions: 5
localDefinitions: 1
effectfulDefinitions: 2
cognitive: 27
traits: 0
instances: 0
enums: 1
structs: 0
effects: 0
typeAliases: 0
lines: 48
codeLines: 37
commentLines: 1
docCommentLines: 6
blankLines: 4
commentPercent: 15
longestLine: 122
linesOverLimit: 3
datalogRules: 0
datalogFacts: 0
widestReturn: 1
widestEffectSurface: 1
widestDatalogDependencyBreadth: 0
deepestDatalogDependency: 0
mostRecursiveDatalogPredicates: 0
longestFlixdocResultCharacters: 28
tests: 1
docCoveragePercent: 25
purityPercent: 75

where each measure peaks
  longest            11 lines                           Json.encode  (src/Json.flix:15)
  densest            1.1 complexity/line                Json.size  (src/Json.flix:33)
  most-complex       11 complexity                      Json.size  (src/Json.flix:33)
  deepest            3 levels nested                    Json.size  (src/Json.flix:33)
  widest             2 parameters                       Json.size  (src/Json.flix:33)
  widest-effect-surface 1 declared effect               Json.write  (src/Json.flix:42)
  longest-flixdoc-result 28 rendered characters         Json.decode  (src/Json.flix:8)
  crammed-line       43 tokens on one line              Json.encode  (src/Json.flix:24)
  most-coupled       3 modules called, call-instability 1.00   Json
  highest-change-impact 1 dependent module, definition-call fan-in   Json

smells: 10
  src/Json.flix:24  crammed-line         (43 tokens, over 35  [Json.encode])
  src/Json.flix:24  line-too-long        (122 UTF-16 code units, over 100)
  src/Json.flix:27  undocumented-public  (public with no doc comment  [Json.depth])
  src/Json.flix:33  dense                (1.1 complexity per line, over 1  [Json.size])
  src/Json.flix:38  crammed-line         (37 tokens, over 35  [Json.size.loop])
```

(Abridged — each ranking lists its top few, not one.)

Every finding names what was exceeded and by how much, so the threshold is arguable rather
than mysterious. Reporting alone never fails your build; CI can opt into a gate explicitly:

```console
./flixw metrics report --format sarif --fail-on warning
```

The gate exits 1 when an unsuppressed finding meets or exceeds the selected registry severity
(`note`, `warning`, or `error`), after still writing the complete report. Usage or analysis
failures remain exit 2, so policy failure is distinguishable from a broken run.

For a new project, initialize the reviewed files and get the CI command in one step:

```console
$ ./flixw metrics init
created .flixw-metrics.properties
created metrics-baseline.json
CI: ./flixw metrics report --baseline metrics-baseline.json --fail-on-new warning
```

The generated policy contains documented, commented examples and therefore leaves every default
active. The baseline is the complete native JSON report from the normal compiler-backed analysis,
including provenance and effective policy. `init` refuses to overwrite either file; move or remove
an existing file explicitly before trying again. Review and commit both files, then use the printed
command in CI.

To adopt metrics manually without making existing debt block every change, capture a native JSON
report on the branch you want to treat as the baseline, commit it, and gate only regressions:

```console
./flixw metrics report --format json > metrics-baseline.json
./flixw metrics report --baseline metrics-baseline.json --fail-on-new warning
```

`--fail-on-new` exits 1 only for a new finding, or for the same stable finding whose threshold
multiple increased, at or above the selected severity. Existing and improved findings do not
fail it. A moved finding is new at its new location and resolved at its old location, because
location is part of the observation ID. The report separately lists new, worsened, and resolved
observations and counts those retained; SARIF labels current results `new`, `updated`, or
`unchanged`.

The baseline must be a native JSON report with the current `schemaVersion` and the same effective
rule/suppression policy. A missing, malformed, stale-schema, or differently configured baseline
exits 2 instead of silently comparing unlike policies. Relative baseline paths resolve from the
Flix project root. `--baseline` can be used without a gate to inspect the delta, and `--fail-on`
can still be combined with it when both all current debt and new debt should be enforced.

Four output formats:

| | for |
|---|---|
| `--format text` | a terminal (the default) |
| `--format json` | a program — full per-definition and per-module lists, `schemaVersion` at the top |
| `--format md` | pasting into a pull request, ordered as a work plan |
| `--format sarif` | GitHub code scanning, so findings land inline on the diff |

```console
./flixw metrics report --format md
./flixw metrics report --format sarif > metrics.sarif
```

Only the report goes to stdout — the compiler's own dependency-resolution chatter goes to
stderr — so redirecting `--format json` gives you a file that parses.

Markdown, JSON, and SARIF reports identify the source commit, dirty working-tree state,
analyzer version, compiler artifact, complete measurement-input digest, and measurement time.
Provenance is recomputed on every invocation, including cache hits; a warm report therefore
carries the same identifying information as a cold one.

## Configure findings

Measurements and rankings are facts about the source. Findings are policy, so a project can
override that policy in a tracked `.flixw-metrics.properties` file:

```properties
rules.definition-too-long.limit=100
rules.dense.enabled=false
rules.noisy-flixdoc-parameters.limit=180

exclusions.generated.file=src/generated/**
exclusions.generated.reason=generated by the schema compiler

exclusions.embedded.file=src/assets/BootFontData.flix
exclusions.embedded.reason=generated embedded font data

suppressions.legacy.rule=too-many-parameters
suppressions.legacy.subject=Legacy.*
suppressions.legacy.reason=replace after the protocol migration
suppressions.legacy.until=2026-12-31
```

Every numeric rule accepts `.limit`; every rule accepts `.enabled=true|false`. Suppressions
select a rule (or `*`) and at least one portable `file` or `subject` glob. `*` stays within one
path component and `**` crosses directories. A non-empty reason is mandatory; `until` is
optional and remains active through that date. Unknown keys, rules, malformed values, and
incomplete suppressions stop with a diagnostic instead of silently weakening the policy.

Exclusions accept a portable `file` glob and require a reason. The compiler still receives those
sources—generated definitions may be dependencies—and compiler census totals such as definitions
remain complete. Excluded files do not contribute lexer-derived line totals, API coverage,
file-attributed findings, or file-attributed rankings. Module coupling and other module-wide facts
remain because they have no single source to remove. `files` is therefore the compiled input count;
`analyzedFiles` and `excludedFiles` make the policy boundary explicit, and every output format lists
the matched files and reasons.

Configuration never changes cached measurements and is deliberately absent from the
measurement-cache key: editing policy takes effect immediately on a warm run. Reports include the
effective rule state, limits, exclusions, and policy digest. Nothing fails the build unless a
caller supplies `--fail-on` or `--fail-on-new`.

If you change policy after `init`, recapture `metrics-baseline.json` with `--format json` before
committing: baseline comparison deliberately rejects reports produced under different policy.

## What the numbers mean

The default thresholds are checked against pinned real-world projects, including the compiler's
own Prelude. See the [calibration report](docs/CALIBRATION.md) for the corpus, distributions,
false-positive review, timings, and rated follow-up suggestions.

Three things get measured, and where each comes from is deliberate:

- **From the compiler's typed AST** — definitions, modules, local definitions, declared
  effects, branch complexity, nesting depth, return shape, formal parameter names and types,
  documentation, Datalog rules and facts, and module coupling. Counting `def` by scanning text
  gets comments, strings and nested definitions wrong, which is the reason this plugin exists.
- **From the compiler's lexer** — code, comment, doc-comment and blank lines, and tokens per
  line. A line with code and a trailing comment is code; a line inside a block comment is
  not.
- **From thresholds over both** — the findings.

Seven measures are worth knowing about because they catch what totals hide:

**Cognitive complexity is nesting-weighted.** Five nested conditions cost more than five
consecutive ones. Divided by lexer-confirmed code lines, it separates *long* from *hard*: a
hundred readable lines and ten dense ones can total the same, and the second is the one worth
opening. Blank and comment-only padding cannot improve the ratio. It is the `densest` ranking
and the `dense` finding. Rankings show every definition, but only definitions with at least four
code lines are eligible for a `dense` finding; a tiny branch-heavy helper is useful context, not
actionable density debt. Human reports mark each ranking entry's rule eligibility, and native JSON
carries `eligible` plus an `ineligibilityReason`; `true` means no structural prerequisite excludes
the entry, not that it crossed a threshold. SARIF carries the minimum-size condition in its rule
metadata.

**FlixDoc signature load measures the generated API, not source wrapping.** FlixDoc renders an
outer formal-parameter span as `(name: Type, ...)`; `flixdocParameterCharacters` counts its Unicode
characters exactly as the compiler formats the types. Public production functions over 140 are a
`noisy-flixdoc-parameters` note and appear in the `longest-flixdoc-parameters` ranking. The pinned
corpus has a median of 33, p95 of 105, and 29 outliers over 140—including one-parameter functions
whose type alone renders at 146–194 characters—so this is not another parameter-count rule.
`flixdocResultCharacters` separately measures the compiler-formatted result type and
`longest-flixdoc-result` ranks it; result width remains contextual rather than a finding.

**Redundant parameter prose is deliberately narrow.** `parameterDocEntries` recognizes Markdown
list items such as ``- `input`: ...`` only when `input` is an actual outer formal parameter.
`redundantParameterDocEntries` counts entries whose descriptions contain only that name and
boilerplate such as “the given argument”; two produce a `redundant-parameter-doc` note. Free prose,
unrelated named lists, and useful descriptions are not guessed at. The signature already supplies
names and types; documentation should add constraints, relationships, or behavior.

**Effect-surface width measures capabilities promised to callers.** `effectCount` is the size of
the compiler-normalized declared effect set; pure definitions are zero. The
`widest-effect-surface` ranking retains the effect names in native JSON so a broad orchestration
boundary is inspectable rather than reduced to a number. It is a measurement, not a finding:
combining several effects is often exactly an application's job.

**Datalog dependency breadth measures relations read by derived rules.**
`datalogDependencyBreadth` is the number of distinct relational predicate names appearing in the
bodies of a definition's constraints. Repeated atoms count once, recursive reads count, and facts,
guards, and functional predicates do not inflate it. `widest-datalog-dependency` ranks the
definitions with the broadest relation dependency surface; no threshold turns that context into
debt. `datalogDependencyDepth` counts predicate levels on the longest path after mutually
recursive predicates are collapsed into one component, so cycles cannot make depth infinite;
facts-only definitions are zero. `recursiveDatalogPredicates` retains the sorted cycle members.
The `deepest-datalog-dependency` and `most-recursive-datalog` rankings expose both dimensions.

**Fan-in estimates definition-call change impact.** `highest-change-impact` ranks modules by how
many other project modules directly call their definitions. It uses the same resolved call graph
as coupling, but reverses the question from “how many modules does this one call?” to “how many
modules may need review if this one changes?” The label always says definition-call fan-in because
type, trait, effect, and data dependencies are outside this deliberately narrow graph.

**Parameters and crammed lines are attributed to the local definition that owns them.** In
the sample above, `Json.size.loop` is blamed for its own crammed line — not `Json.size`, the
enclosing definition, which is where a text scanner would send you.

Tests are judged differently rather than exempted: a long test is usually a table of cases,
and an undocumented test is not a gap in a public API.

Full details of which number comes from where: [docs/COMPILER-SDK.md](docs/COMPILER-SDK.md).
API docs: [wstein.github.io/flixw-metrics](https://wstein.github.io/flixw-metrics/).

## It is fast the second time

A cold run is a few seconds — about 5 on the sample above — because the compiler type-checks
its own standard library before it reaches your code. A warm run is about 0.4s, which is
mostly JVM startup.

What is cached is the **measurements**, never the report: they are facts about your source
and cannot go stale while the key holds. Findings and formatting are recomputed every run,
so an adjusted threshold takes effect immediately instead of at the next cache miss. The key
covers your sources, `flix.toml`, the pinned compiler, this plugin's version, and the plugin
artifact bytes — change any of them and it recomputes. The artifact fingerprint also prevents
two local `development` builds from sharing stale measurements. Every cache failure is a miss,
never a stale answer.

Entries live where flixw says (`FLIXW_PLUGIN_CACHE`) and go away with the plugin.

## Safety

A SHA-256 proves an artifact has the bytes you expected. It does **not** make a plugin safe:
plugins are unsandboxed code running as their caller, and flixw prints that warning on every
invocation, not only at install.

This plugin reads your source through the compiler flixw already verified. It does not
download a compiler, choose one, or run anything else.

## Supported compilers

Flix **0.75.x**. The engine reads the compiler's internal AST, which carries no
compatibility promise, so it is compiled against one release and checks what is actually in
front of it before running:

```console
$ ./flixw metrics capabilities
{
  "compilerJar": "/Users/you/Library/Caches/flixw/compilers/flix-0.75.3-bf123cdb....jar",
  "hasFlixApi": true,
  "hasReflectionApi": true,
  "hasNativeMetrics": false,
  "missing": []
}
```

An unsupported compiler gets a sentence naming what is missing, rather than a wrong number
or a stack trace. Supporting another Flix generation is one adapter class — see
[docs/COMPILER-SDK.md](docs/COMPILER-SDK.md).

## Building it yourself

```console
sh scripts/test.sh                 # lint, build, tests
sh scripts/package.sh 0.1.7        # dist/plugin.jar and dist/SHA256SUMS
sh scripts/calibrate-corpus.sh /tmp/flixw-calibration-results
sh scripts/measure-performance.sh /tmp/flixw-performance.json
```

`./mill` bootstraps the pinned build tool, and `scripts/fetch-flix.sh` downloads the Flix
release the engine is written against and checks its digest — so "it builds here" means
something.

The corpus command is the slower, networked dogfood check, so it is scheduled weekly rather than
run on every pull request. It clones only the full commits recorded in
[`calibration/corpus.json`](calibration/corpus.json), rejects any metric drift, and leaves complete
JSON reports in the requested output directory. See the
[calibration report](docs/CALIBRATION.md#reproducing-the-pinned-corpus) for offline reuse and the
intentional-update procedure.

The performance command runs five cold and five warm packaged-plugin samples, records every timing,
and checks cold median, warm median, and warm/cold ratio against
[`calibration/performance-budget.json`](calibration/performance-budget.json). The scheduled workflow
retains those measurements, making budget changes reviewable against CI history rather than a
single favorable run.

A jar is not byte-reproducible across machines, because `jar` records timestamps: a local
build will not have the digest the release does. The digest under [Install](#install) is the
published artifact's, and is what you should verify against.

## License

Apache-2.0. See [LICENSE](LICENSE).
