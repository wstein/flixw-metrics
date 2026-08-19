# Reflection compatibility contract

`flixw-metrics` uses only the compiler JAR flixw selected and verified for the current
project. It does not download a compiler, choose a version, or load an arbitrary JAR from a
project setting.

## The gate names what the engine calls

The capability gate lists the exact members `ReflectionEngine` reflects against, and nothing
else:

| member | why |
|---|---|
| `ca.uwaterloo.flix.api.Flix` + `check/0` + `setOptions/1` | the typed root comes from here |
| `ca.uwaterloo.flix.api.Bootstrap` + `check/1` | loads the project and its dependencies |
| `Bootstrap.bootstrap(Path, …, PrintStream)` | the static entry point, matched by shape |
| `ca.uwaterloo.flix.util.Options$.Default/0` | the options the run is configured with |
| `ca.uwaterloo.flix.util.Formatter$.getDefault/0` | passed to `bootstrap` |

This list is not decoration. An earlier gate checked `Flix.check()` and a two-argument
`addFile` — and the engine calls neither `addFile` nor anything else that gate covered. A
compiler that kept `addFile` while changing `Bootstrap.bootstrap` therefore **passed the gate
and then failed mid-run with a reflection error**, which is exactly the outcome the gate
exists to turn into a sentence. If the engine starts calling something new, it goes in the
gate in the same commit.

A version string is never evidence. The gate asks the JAR.

## Why there are two JVMs

The compiler is loaded through the **application class path** of a second JVM, launched with
`-cp plugin.jar:flix.jar`. That is forced, not chosen.

An isolated `URLClassLoader` was tried and does not work. The Flix standard library imports
Java classes that live inside `flix.jar` itself — `dev.flix.runtime.Global` among them — and
the compiler resolves those through the application class path rather than through the loader
that defined it. Loading Flix from a child loader fails with four `E1803 Undefined Java class`
errors before a single line of the project is typed. Setting the thread context class loader
does not change it. Both were measured against a real compiler.

The consequence has to be lived with: `flix.jar` bundles ASM **unshaded** at
`org/objectweb/asm`, plus JLine, gson and json4s, and on a flat class path they are visible to
this plugin. **Any dependency added to this plugin must be shaded**, because otherwise which
copy wins is decided by class-path ordering rather than by intent.

Class loading *for inspection* is a different question and is still isolated: the gate uses
`Class.forName(name, false, loader)`, which never runs compiler code, so it can afford a
child loader parented to the platform loader.

## Cost, and where it goes

Measured against a two-definition project with Flix 0.75.3:

| | |
|---|---:|
| full cold report | ~4.5s |
| — the compiler typing **its own standard library** | ~3.4s |
| — typing the project's own sources | ~0.5s |
| — the second JVM (the bridge hop) | ~0.27s |
| — JVM boot | ~0.19s |
| cached report | **~0.16s** |

The shape of that table is the whole reason the cache exists. Process architecture is under
10% of the run; the standard library is 87%. Optimising the hops would have been optimising
the wrong thing.

## The cache

Keyed on everything that can change the answer, and nothing else:

- the compiler JAR's digest — it *contains* the standard library, so it decides typing
- this plugin's version — a new build may count the same root differently
- `flix.toml`'s digest, or its absence — it selects dependencies that participate in typing
- every project source path **with** its digest, sorted, relative to the project root

Paths as well as contents, so a **deleted** file invalidates: `files` is part of the report.
Relative paths, so the same tree checked out at two locations is one entry rather than two.

Entries live wherever `FLIXW_PLUGIN_CACHE` points — flixw's own answer to "where may a plugin
keep derived data", which it deletes when this plugin is removed or purged. The directory is
**not** chosen here. An earlier draft picked its own path under the cache; it worked, and it
left bytes that nothing would ever clean up.

A wrapper too old to set the variable leaves the plugin **uncached** rather than falling back
to a guessed path, for the same reason: an uncollected directory is worse than a slow run.

The rule the implementation follows everywhere: **every failure is a miss**. An unreadable
entry, a truncated one, an unknown `schemaVersion`, a cache directory that cannot be written
— each costs a recomputation and none can produce a wrong number. A cache that is merely slow
is working; a cache that disagrees with the compiler is worse than no cache at all.

`FLIXW_PLUGIN_CACHE` is optional. Without it the plugin runs uncached rather than failing.

## Lifecycle

`./flixw plugin remove flixw-metrics` deletes these entries with the plugin. `./flixw wrapper
--purge` collects them once the plugin is no longer installed, without consulting a usage
marker — the marker belonged to the plugin and went with it, so the age rule would otherwise
retain them for ever as "never seen used". While the plugin *is* installed, flixw does not
touch them: that would be a cache invalidation this plugin could not know had happened.
