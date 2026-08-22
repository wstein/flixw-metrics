# flixw-metrics

Code metrics for [Flix](https://flix.dev) projects, counted by the compiler rather than by
reading the text. It is a plugin for [flixw](https://github.com/wstein/flixw) and measures
your code with the exact compiler your project already pinned.

> **Experimental, third-party, unaffiliated** with the Flix project. A plugin is ordinary
> code running as you — see [Safety](#safety) before installing anything.

## Install

```console
./flixw plugin install metrics 0.1.4 \
  https://github.com/wstein/flixw-metrics/releases/download/v0.1.4/plugin.jar \
  --sha256 f3ee665910a1953a1aa8a387155c43babac79246f0d5d31c0a8a2b559f53faea
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
tests: 1
docCoveragePercent: 25
purityPercent: 75

where to look first
  longest            11 lines                           Json.encode  (src/Json.flix:15)
  densest            1.1 complexity/line                Json.size  (src/Json.flix:33)
  most-complex       11 complexity                      Json.size  (src/Json.flix:33)
  deepest            3 levels nested                    Json.size  (src/Json.flix:33)
  widest             2 parameters                       Json.size  (src/Json.flix:33)
  crammed-line       43 tokens on one line              Json.encode  (src/Json.flix:24)
  most-coupled       3 modules used, instability 1.00   Json

smells: 10
  src/Json.flix:24  crammed-line         (43 tokens, over 30  [Json.encode])
  src/Json.flix:24  line-too-long        (122 columns, over 100)
  src/Json.flix:27  undocumented-public  (public with no doc comment  [Json.depth])
  src/Json.flix:33  dense                (1.1 complexity per line, over 1  [Json.size])
  src/Json.flix:38  crammed-line         (37 tokens, over 30  [Json.size.loop])
```

(Abridged — each ranking lists its top few, not one.)

Every finding names what was exceeded and by how much, so the threshold is arguable rather
than mysterious. Nothing here fails your build.

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

## What the numbers mean

Three things get measured, and where each comes from is deliberate:

- **From the compiler's typed AST** — definitions, modules, local definitions, declared
  effects, branch complexity, nesting depth, return shape, Datalog rules and facts, module
  coupling. Counting `def` by scanning text gets comments, strings and nested definitions
  wrong, which is the reason this plugin exists.
- **From the compiler's lexer** — code, comment, doc-comment and blank lines, and tokens per
  line. A line with code and a trailing comment is code; a line inside a block comment is
  not.
- **From thresholds over both** — the findings.

Two measures are worth knowing about because they catch what totals hide:

**Cognitive complexity is nesting-weighted.** Five nested conditions cost more than five
consecutive ones. Divided by lines, it separates *long* from *hard*: a hundred readable
lines and ten dense ones can total the same, and the second is the one worth opening. That
ratio is the `densest` ranking and the `dense` finding.

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
covers your sources, `flix.toml`, the pinned compiler and this plugin's version — change any
of them and it recomputes. Every cache failure is a miss, never a stale answer.

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
sh scripts/package.sh 0.1.4        # dist/plugin.jar and dist/SHA256SUMS
```

`./mill` bootstraps the pinned build tool, and `scripts/fetch-flix.sh` downloads the Flix
release the engine is written against and checks its digest — so "it builds here" means
something.

A jar is not byte-reproducible across machines, because `jar` records timestamps: a local
build will not have the digest the release does. The digest under [Install](#install) is the
published artifact's, and is what you should verify against.

## License

Apache-2.0. See [LICENSE](LICENSE).
