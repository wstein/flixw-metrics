# Compiler compatibility contract

`metrics` uses only the compiler JAR flixw selected and verified for the current
project. It does not download a compiler, choose a version, or load an arbitrary JAR from a
project setting.

## The SDK exists because flix.jar has no stable ABI

`Bootstrap`, `TypedAst` and everything under them carry no compatibility promise, and are
reorganised between releases. A plugin that spread knowledge of those types through its own
code would inherit that instability everywhere, and every new Flix would be an edit in a dozen
places.

So it is confined to one file per compatibility family. `dev.flixw.metrics.sdk.CompilerModel` is the only thing the
rest of the plugin knows about a compiler, it is plain Java, and it returns **counts and
strings, not compiler types** — a richer interface handing back declarations or an AST cursor
would put compiler concepts straight back into the callers it exists to protect.

| layer | knows about Flix | language |
|---|---|---|
| `Main`, `Metrics`, `ResultCache`, `SourceMetrics` | nothing | Java |
| `sdk.CompilerModel`, `sdk.Adapters` | nothing | Java |
| `flix0600`: `dev.flixw.metrics.adapter.Flix0600Adapter` | everything | Scala |
| `flix0610`: `dev.flixw.metrics.adapter.Flix0610Adapter` | everything | Scala |
| `flix0661`: `dev.flixw.metrics.adapter.Flix0661Adapter` | everything | Scala |
| `flix0672`: `dev.flixw.metrics.adapter.Flix0672Adapter` | everything | Scala |
| `flix0680`: `dev.flixw.metrics.adapter.Flix0680Adapter` | everything | Scala |
| `flix0753`: `dev.flixw.metrics.adapter.Flix0753Adapter` | everything | Scala |

Every adapter, including the newest family, is a peer module compiled against only its own compiler
generation. Supporting another Flix generation is an isolated adapter module and a line in `Adapters.KNOWN`.
It is explicitly *not* an edit to the report, the smells, the formats, the cache or the CLI. Each
module compiles against the oldest verified compiler in its family; none of those compilers is packaged.

`SDK_VERSION` is declared and unused. The moment an adapter ships separately from this jar,
the two need a way to say whether they agree; while every adapter is compiled by this build and
packaged in the same jar, they cannot disagree, so nothing checks it. Today's verified scope is
**Flix 0.60.0 via
`Flix0600Adapter`; Flix 0.61.0 and 0.65.0 via `Flix0610Adapter`; Flix 0.66.1 and 0.67.1 via
`Flix0661Adapter`; Flix 0.67.2 via
`Flix0672Adapter`; Flix 0.68.0 and 0.75.2 via
`Flix0680Adapter`; and Flix 0.75.3 and 0.76.0 via
`Flix0753Adapter`.** Each suffix names the oldest
verified release in its family, not a closed range or the current compiler pin. The investigation supporting each release
is recorded under [`docs/compiler-compatibility`](compiler-compatibility/README.md).

### Adapters are selected by linkage, not by version string

`Adapters.resolve()` checks each known adapter's bytecode-derived ABI contract, newest first, and
instantiates the first compatible one. The explicit check matters because the JVM may resolve a
method reference only when that code path first executes; construction alone does not prove the
whole adapter links. A fork reporting an unfamiliar version may still link; a compiler reporting a
familiar one may not. The JVM knows, and asking it is cheaper than maintaining a table of which
versions are secretly compatible.

`LinkageError` is caught alongside the reflective exceptions, deliberately: a missing AST type
arrives as `NoClassDefFoundError`, which is an `Error` and not an `Exception`.

## Why the engine is Scala

Flix's AST is a sealed hierarchy, so typed patterns over it are checked. Stock 0.75.3 and 0.76.0
both have 76 `Expr` constructs. The classifier intentionally has a catch-all because only a small
subset contributes directly to metrics, while generic `Product` descent still reaches their
children. Consequently a new AST alternative does not by itself produce an inexhaustive-match
failure: every compiler repin also needs semantic fixtures and corpus comparison.

The reflective predecessor could not know that. It classified nodes by simple class name and
ignored the rest in silence, which cost precisely what it sounds like: it named a
`TypeMatchRule` that does not exist and missed the `ExtMatchRule` that does, so every extensible
match was undercounted and nothing said so. Typed patterns prevent misspelling or matching a node
that does not exist; release-specific semantic regressions prevent new or reshaped nodes from
passing unnoticed.

Scala also caught, at compile time, that `Bootstrap.bootstrap` takes its formatter and stream as
**implicit** parameters. Erasure makes an implicit parameter list look like any other, so the
reflective version passed four arguments to a two-argument method and could not have known.

The capability gate stays Java for the same reason the rest of the shell does: it has to load
and answer on a machine where the adapter would not link at all.

## The gate names what the engine links against

The capability gate reads every packaged adapter family and checks every Flix class, field,
constructor, and method each one links against using its exact JVM descriptor. Following generated
nested adapter classes keeps Scala's closure placement from creating an unchecked call. The resulting
contract includes:

| member | why |
|---|---|
| `TypedAst` definitions, branch/rule nodes, constraints and predicates | the AST the adapter pattern-matches |
| `Type`, `TypeConstructor` and the tuple/record/pure shapes | return widths and effect surfaces |
| `Input$RealFile`, `Input$VirtualFile` | how project and compiler-library files are selected |
| `Lexer$.lex`, `TokenKind.isComment` | line and token measurements |
| `FormatType$.formatType` and its default-argument accessors | browser-visible FlixDoc widths |
| `ca.uwaterloo.flix.api.Flix.check` and `setOptions` | the typed root comes from here |
| `ca.uwaterloo.flix.api.Bootstrap.check` and `Bootstrap$.bootstrap` | loads the project and its dependencies |
| `Options$.Default`, `Formatter$.getDefault`, and Scala `MODULE$` fields | compiler setup and singleton linkage |

The gate also loads `dev.flix.runtime.Global` without initialization. That class is not referenced
by adapter bytecode, but the compiler's standard library loads it through the application class
path. This catches an otherwise structurally compatible compiler built for a newer JVM before
measurement begins; the official 0.66.0 artifact is the boundary regression.

This list is not decoration. An earlier gate checked `Flix.check()` and a two-argument
`addFile` — and the engine calls neither `addFile` nor anything else that gate covered. A
compiler that kept `addFile` while changing `Bootstrap.bootstrap` therefore **passed the gate
and then failed mid-run with a linkage error**, which is exactly the outcome the gate
exists to turn into a sentence. The gate is derived from the adapter bytecode, and its regression
test checks the pinned compiler against that complete contract. A new adapter call therefore becomes
a capability requirement automatically instead of depending on a parallel handwritten edit.

A version string is never evidence. The gate asks the JAR.

## Why there are two JVMs

The compiler is loaded through the **application class path** of a second JVM, launched with
`-cp plugin.jar:flix.jar`. That is forced, not chosen.

The bridge also starts with a 64 MiB thread stack. Flix's constraint generation is recursive, and
the pinned compiler documents 64 MiB as its minimum worker stack; the usual 1–2 MiB JVM default
overflowed while checking the pinned `flix-game-engine` calibration target. Keeping the larger
stack on the isolated bridge avoids imposing it on ordinary wrapper commands.

An isolated `URLClassLoader` was tried and does not work. The Flix standard library imports
Java classes that live inside `flix.jar` itself — `dev.flix.runtime.Global` among them — and
the compiler resolves those through the application class path rather than through the loader
that defined it. Loading Flix from a child loader fails with four `E1803 Undefined Java class`
errors before a single line of the project is typed. Setting the thread context class loader
does not change it. Both were measured against a real compiler.

The consequence has to be lived with: `flix.jar` bundles ASM **unshaded** at
`org/objectweb/asm`, plus JLine, gson and json4s. Flix 0.76 also bundles Byte Buddy and jsr305.
On a flat class path all of them are visible to this plugin. **Any dependency added to this plugin
must be shaded**, because otherwise which copy wins is decided by class-path ordering rather than
by intent.

Class loading *for inspection* is a different question and is still isolated: the gate uses
`Class.forName(name, false, loader)`, which never runs compiler code, so it can afford a
child loader parented to the platform loader.

## What is measured, and from where

| metric | source |
|---|---|
| `definitions`, `traits`, `instances`, `enums`, `structs`, `effects`, `typeAliases` | the typed root, filtered to this project |
| `modules` | the namespaces the definitions' own symbols carry |
| module `fanIn`, `fanOut`, `instability` | resolved cross-module direct definition calls |
| `localDefinitions` | `LocalDef` nodes — definitions the outer signature hides |
| `effectfulDefinitions`, `purityPercent`, per-definition `effectCount` and `effects` | the compiler-normalized *declared* effect set on each signature; a saturated polymorphic effect is atomic |
| per-definition `handlers`, `handledOperations`, `maxHandlerOperations`, `resumptions` | typed handler literals, their clauses, the widest handler, and direct continuation-parameter calls |
| per-definition `effectDetails` | each effect constructor with its argument count and compiler-rendered type arguments; does not widen `effects` |
| `effectDeclarations` | each effect declaration's qualified name, source location, type-parameter count, and source-ordered operation names and arities |
| `cognitive` | branches weighted by nesting, plus boolean operators and match guards |
| `returnWidth` | a tuple's arity, or a record's top-level field count |
| `flixdocParameterCharacters` | Unicode characters in FlixDoc's generated outer formal-parameter span |
| `flixdocResultCharacters` | Unicode characters in FlixDoc's compiler-formatted result type |
| `parameterDocEntries`, `redundantParameterDocEntries` | strict Markdown entries for real formal names, and the subset containing only signature boilerplate |
| `datalogRules`, `datalogFacts` | constraints with and without a body |
| `datalogDependencyBreadth`, `datalogDependencies` | distinct relational predicate names read by constraint bodies |
| `datalogDependencyDepth` | predicate levels in the longest dependency path after recursive components are collapsed |
| `recursiveDatalogPredicateCount`, `recursiveDatalogPredicates` | predicates participating in direct or mutual dependency cycles |
| `tests`, `docCoveragePercent` | `@Test` annotations and doc comments on the production public surface |
| `lines`, `codeLines`, `commentLines`, `docCommentLines`, `blankLines` | the compiler's own lexer |
| `longestLine`, `linesOverLimit` | the source text |
| `smells` | thresholds over all of the above |

### Cognitive complexity is nesting-weighted

Each branch counts once for every branch enclosing it, so five nested conditions cost more
than five consecutive ones — a flat count calls them equal, and they are not equal to read.
Boolean `and`/`or` and match guards add a path without adding a branch construct, so they are
counted too; without them a long boolean chain reads as trivial.

`cognitiveDensity` is that divided by lexer-confirmed code lines inside the definition, and it
is the measure that separates length from difficulty: a hundred readable lines and five dense
ones can score the same in total, and the second is the one worth opening. Blank lines and
comment-only lines are excluded, so padding the span cannot improve the score. The ranking includes
every definition, while the finding requires at least four code lines so a one-expression helper
does not become debt merely for containing a branch. That eligibility floor lives in the rule
registry and is used by threshold evaluation. Each native JSON ranking carries structural
`eligible` and `ineligibilityReason` fields, and human reports mark ineligible entries in place;
eligibility does not mean that the threshold was crossed. SARIF discloses the floor in its rule
metadata.

### Handler shape is separate from cognitive complexity

An effect handler literal increments `handlers`; its typed rule list contributes to
`handledOperations`, and the largest such list becomes `maxHandlerOperations`. A resumption is a
direct closure application whose callee is the final formal parameter of that handler rule. The
symbol comparison comes from the typed AST, so an unrelated variable with the same source name is
not counted. Conversely, assigning the continuation to another variable and calling that alias is
outside the deliberately narrow definition and is not inferred.

Handler rules retain their existing contribution to `cognitive`. Collecting the four handler
measurements does not add branches or alter nesting weights. They are emitted as per-definition
native JSON facts only: there is no project summary, ranking, finding, or threshold.

This additive measurement changes machine compatibility intentionally. `CompilerModel.DefInfo`
adds four integer components (the named builder defaults each to zero), the internal wire format
is version 10 so version-9 measurement caches become misses, and native JSON schema 30 requires the
four non-negative fields. A schema-29 baseline is rejected rather than compared as if the
measurements existed. Text, Markdown, SARIF, CLI, and capability-JSON shapes are unchanged; the
bytecode-derived capability contract automatically includes the extra typed-AST members used by
each adapter.

Effect declarations are measured the same additive way. `CompilerModel` gains `EffectInfo` and
`EffectOperationInfo` records and a `Model.effectDeclarations` list; each adapter reports the
declaration's qualified name, source location, type-parameter count, and its operations in source
order with compiler-derived arities. Flix 0.60.0 reports `typeParameters` as zero explicitly,
since that release's AST carries no such member. The aggregate `effects` per-definition surface
and `effectCount` keep their existing capability semantics unchanged; declaration detail is
additive and appears only in full native JSON. It changes the internal wire format from 10 to 11
and native JSON from schema 30 to 31; older caches safely miss.

### Effect instances do not redefine the capability surface

`effects` remains the sorted, distinct set of constructors in the compiler-normalized declared
effect formula. `effectDetails` adds an `EffectDetail` for each distinct instantiation: constructor
name plus an ordered list of type arguments rendered by the same compiler formatter used for
FlixDoc signatures. `argumentCount` is derived from that list. Sorting by constructor and rendered
arguments makes output deterministic for one typed program.

The adapter stops when it reaches a saturated effect constructor. For
`Outer[Inner[Int32]]`, the surrounding formula therefore contains one `Outer` capability whose one
rendered argument is `Inner[Int32]`; `Inner` is not another member of that formula. Two definitions
using `Outer[Inner[Int32]]` and `Outer[Inner[String]]` have identical `effects` and distinct
`effectDetails`.

Rendered arguments are compiler-normalized strings, not a new cross-release type language. They
are deterministic within the compiler that produced the report and remain useful across families
as ordinary Flix type syntax, but a compiler release may deliberately change formatting. Older
adapter families predate polymorphic effects and explicitly pair every existing constructor name
with an empty argument list.

This additive field changes the internal wire format from 11 to 12 and native JSON from schema 31
to 32. Version-11 measurement caches safely miss. Schema 32 requires `effectDetails` on every full-report
definition. Existing `effects`, `effectCount`, purity, summary, rankings, findings, text, Markdown,
SARIF, CLI, and capability-JSON shapes are unchanged.

### FlixDoc parameter noise comes from the typed signature and its documentation

FlixDoc renders each public definition's outer formals as `(name: Type, ...)`, independently of
how the source declaration is wrapped. The adapter uses the compiler's own type formatter, removes
the synthetic Unit parameter of a nullary definition, and records the Unicode-character length of
that exact span. It also carries outer formal names and raw Markdown documentation through the
measurement cache. Policy remains outside the adapter: `Thresholds` applies the configurable
140-character boundary, while `DocumentationMetrics` recognizes only Markdown list entries whose
labels exactly match real formals.

The prose rule intentionally does not infer meaning from ordinary sentences. An entry is redundant
only when its remaining words are the parameter name and a small boilerplate vocabulary; at least
two such entries are required. This keeps named lists, meaningful parameter contracts, and common
Prelude prose out of the rule. Native JSON exposes the rendered length and both entry counts, but
not the complete doc text; the latter is cached only so policy can be recomputed on a warm run.

The result type is measured separately with the same compiler formatter. It has no source-wrapping
component and no threshold: `longest-flixdoc-result` ranks the generated API surface while native
JSON exposes `flixdocResultCharacters`. Keeping it separate from parameter width lets consumers
distinguish a complicated input contract from a complicated value returned to callers.

Module coupling is deliberately narrower than general dependency coupling. An edge from A to B
means a project definition in A contains a resolved direct call to a definition in B. Type,
trait, effect, enum, struct, and instance dependencies are not included, so reports call these
values definition-call coupling rather than claiming to describe every way modules can depend on
one another.

The same edge contributes to B's `fanIn`. `highest-change-impact` ranks that reverse count: how many
other project modules directly call definitions in B. It is a review-impact hint rather than a
finding—stable foundations are expected to have high fan-in—and the ranking value explicitly says
“definition-call fan-in” to avoid claiming type, trait, effect, enum, struct, or instance impact.

### Lines are classified by the compiler's lexer, not by scanning for `//`

`Lexer.lex(Source)` and `TokenKind.isComment` are present throughout both verified adapter families — an earlier note here
claimed otherwise and was simply wrong. A line holding code and a trailing comment counts as
code, because it is a line you have to read as code; a line inside a block comment counts as a
comment though nothing on it says so, which is exactly where a text scan goes wrong.

Doc comments are counted separately from ordinary ones. "How much of this is explained" and
"how much of this is commented out" are different questions, and lumping them answers neither.

Lexed rather than read from `Root.tokens`: by the time `check` returns, that map holds only
what later phases still needed, so a file of any size arrives with a handful of tokens and
every line after the first would be counted blank. That is the fork's finding, and the sort of
thing only someone who tried the obvious way first would know.

### Four renderings, one cached run

`text` for a terminal, `json` for a program, `md` for a pull request, `sarif` for a
code-scanning tab. All four are derived from the same `Report`, which is why it carries its
rankings and findings as **data** rather than as text: a cached run must be able to serve a
format it was not originally asked for.

SARIF declares every rule whether or not it fired, so a consumer that builds its UI from the
tool descriptor does not see a different rule set on every run. Module-level findings have no
file and no line, and SARIF requires a region line of at least 1 — those are emitted without a
region rather than with a fabricated line 0.

Markdown puts findings first and totals last, grouped by rule and ordered by declared severity,
then threshold distance. Ten instances of one rule are one policy decision while ten separate
rules are ten, and an ungrouped list hides which it is. Every rule carries a one-line action,
because a finding without one is a complaint.

### A finding is data, not a sentence

```json
{"rule": "too-many-parameters", "subject": "Json.decode", "file": "src/Json.flix", "line": 88,
 "actual": 12, "limit": 5, "unit": "parameters", "overBy": 2.40,
 "note": "widest is a local definition, not the signature",
 "detail": "12 parameters, over 5 (widest is a local definition, not the signature)"}
```

An earlier version carried only a rule, a location and `detail`. That reads fine and is nearly
useless to a consumer: sorting by severity, filtering on "more than twice the limit", or
charting a number over time all mean parsing English back into figures somebody already had.
So the measurement and the limit are fields, and **`detail` is derived from them** — which also
means the two cannot drift apart.

`overBy` is a **multiple** used to order findings within one configured severity. It does not
pretend that twice a line-length limit has the same consequence as twice a nesting limit. The
Markdown work plan orders groups by registry severity first and their worst multiple second,
rather than by group size — sixteen definitions one doc comment short is a chore; one deeply
nested definition can be the more urgent problem, and counting alone puts the chore first.

A finding with **no unit is categorical**: something is absent, and there is no amount by which
it is absent. Those score exactly 1 and sort below anything that actually exceeded a limit.
Modelling one as a magnitude produced `"0 doc comments, over 1"` with `overBy: 0.0` — both
unreadable and sorted least-severe by an accident of arithmetic rather than a decision.

`subject` is separate from `file`/`line` on purpose: a crammed line is located at the line but
*owned* by the local definition holding it, and a module-level finding has a subject and no
location at all.

Each finding also has a deterministic SHA-256 observation ID. Symbol-owned findings derive it from
the rule, semantic subject, and portable file, so adding lines above a definition does not turn one
observation into a false resolution plus addition. Source-only findings use exact line content and
its occurrence ordinal within the file; the ordinal keeps identical offending lines distinct.
Native JSON exposes the result as `id`, and SARIF carries the same value as a partial fingerprint,
so consumers can correlate a finding between formats without parsing its prose. Baseline matching
also accepts the earlier location-sensitive fingerprint during migration.

### Findings are report-only unless a caller opts into a gate

`Thresholds` is the single place a measurement becomes a finding. The default command only
reports: the project that needs a 200-line definition exists and its author knows why. A caller
may explicitly choose `--fail-on note|warning|error`; the full report is still written and exit
status 1 then means policy failure, distinct from usage or analysis failure at status 2.

`--baseline report.json --fail-on-new warning` narrows that policy failure to new warning/error
observations and stable observations whose reported `overBy` increased. The baseline reader runs
in both the outer cache-hit JVM and the compiler bridge without a JSON dependency. It requires the
native report's schema and effective configuration (including scoped suppressions) to match;
incompatible input is a usage failure at status 2. JSON and human reports retain the resolved side
explicitly, while SARIF gives current results its standard baseline states. The comparison also
reports raw summary, definition, and module `measurementDeltas`, even when the changed values remain
below every configured threshold; policy findings are therefore not the only observable progress.

`init` does not have a second measurement implementation. It selects native JSON, follows the same
cache-or-compiler path as `report`, and writes that rendered report to `metrics-baseline.json` after
preflighting both output names. Its `.flixw-metrics.properties` contains comments only, so the
captured effective policy is exactly the documented default. Both files use create-new semantics;
if either already exists, initialization stops without overwriting or creating the other.

`RuleDefinitions` is the single catalog for stable IDs, categories, SARIF severity, descriptions,
default limits, and remediation. A tracked `.flixw-metrics.properties` may override a numeric
limit, disable a rule, or suppress a scoped finding with a mandatory reason and optional expiry.
This policy is applied after cached compiler measurements are read, so changing it takes effect
without invalidating or contaminating the measurement cache.

First-class `exclusions.<label>.file` entries are different from suppressions. The source remains
an input to Flix so references to generated definitions still type-check, and AST census totals
remain complete. The adapter retains lexer classifications per source in the cache; report policy
can therefore subtract excluded code, comment, doc-comment, and blank lines without approximating
Flix's lexer. File-located definitions leave API ratios, findings, and rankings, while module-wide
coupling remains because it cannot honestly be assigned to one file. Reports expose compiled,
analyzed, and excluded file counts plus each matched path and mandatory reason.

**Tests are judged differently, not exempted.** A long test is usually a table of cases, which
is the clearest way to write it; an undocumented test is not a gap in a public API. Reporting
those trains a reader to skim the whole list, and the finding that mattered goes with them.

`too-many-parameters` counts the widest parameter list *anywhere inside* the definition, and
says so when the widest one is a local: a body threading eight accumulators through a local
loop reads as taking two, and the outer signature is exactly what hides it.

Two rules decide which side of that table a number falls on. **Anything that needs meaning
comes from the compiler**: counting `def` by scanning text gets comments, strings and local
definitions wrong, which is the whole reason this plugin exists. **Anything that is a property
of the text is taken from the text**: a line's length is not made truer by type-checking it,
and taking it from the file means it still works when the project does not compile — which is
when someone is most likely to be looking.

`branches` counts *rules*, not `match` expressions: a match with three cases is three paths
through the definition, and counting the match would say one.

`effectfulDefinitions` asks the declared effect rather than inferring from the body, because
the declaration is the promise the definition makes to its callers. `effectCount` measures the
width of that normalized set and `widest-effect-surface` locates its upper tail. Pure is the empty
set. Native JSON retains the sorted names so two equally wide but materially different capability
surfaces are not made indistinguishable. There is deliberately no finding: effect orchestration is
often an architectural boundary rather than a defect.

Documentation and purity percentages exclude both `@Test` definitions and definitions whose
portable project-relative path is under `test/`. If no production public definitions exist,
the percentage is undefined: human reports show `N/A` and JSON emits `null`, rather than
claiming that an empty API has zero-percent documentation or purity.

`returnWidth` is a parameter list in the other direction: a record of ten top-level fields or a
tuple of six is wide for the same reason, read for the same reason, and invisible to every other
measure here. Nested records remain one part of their containing record rather than being
flattened. The metric is gated at the same number as parameters, deliberately — giving them
different limits would say one is more forgivable than the other.

`datalogFacts` are counted apart from rules because a constraint with no body is data written as
code, and a thousand of them is a data file rather than a thousand things to understand. A
`query … from P(x, y)` desugars into a constraint and counts as a rule: it derives a relation
from others, which is what a rule is.

`datalogDependencyBreadth` complements that size count with the number of distinct relational
predicate names read across all derived rules in one definition. Two atoms over `Edge` contribute
one dependency; a recursive `Path` body contributes `Path`; head predicates, facts, guards, and
functional body predicates contribute none. The compiler adapter retains the sorted names and the
`widest-datalog-dependency` ranking points to the owning definition. This too remains a measurement:
the corpus supports meaningful ordering, not a universal smell threshold.

Depth uses an edge from a derived head predicate to each relational body predicate it reads.
Mutually reachable predicates form one strongly connected component before the longest path is
measured; each component is one level, including the terminal input predicate. Thus `A -> B -> C`
has depth three, while `A -> B -> A` has depth one and two recursive predicates. A facts-only
definition has no dependency edge and depth zero. `deepest-datalog-dependency` ranks the former;
`most-recursive-datalog` ranks the number of named cycle participants. Native JSON retains the
sorted recursive names, making direct and mutual recursion distinguishable from an unexplained
count.

### The AST walk is generic

Every Flix AST node is a Scala case class, so the walk uses `productArity`/`productElement`
and reaches all of them without naming a single AST type. An unfamiliar node is traversed
rather than fatal, so a compiler that adds a construct still measures — it simply is not
classified until this plugin learns the name.

Node *simple names* are the classification handle because they are the most stable one
reflection has. Being wrong about a name loses a count, not the run.

The walk is done reflectively rather than against `scala.Product` directly, so that building
this plugin does not require a Flix release on the class path. A metrics plugin that could
only be compiled against the compiler it inspects would be awkward to keep working across
several of them.
