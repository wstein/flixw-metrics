package dev.flixw.metrics.sdk;

import java.nio.file.Path;

/**
 * The stable ABI, because {@code flix.jar} does not have one.
 *
 * <p>Flix's internals carry no compatibility promise: {@code Bootstrap}, {@code TypedAst} and
 * everything under them may be reorganised in any release, and have been. A plugin that spreads
 * knowledge of those types through its own code inherits that instability everywhere, and every
 * new compiler becomes an edit in a dozen places.
 *
 * <p>So the instability is confined instead. This interface is the only thing the rest of the
 * plugin knows about a compiler, and it is <b>Java, and versionless on purpose</b>: what it
 * returns are counts and strings, not compiler types. One implementation per Flix generation
 * sits behind it, written against that generation's AST, and nothing outside those
 * implementations imports {@code ca.uwaterloo.flix}.
 *
 * <p>The practical consequence is the one worth stating. Supporting a new Flix means writing an
 * adapter and registering it. It does not mean touching the report, the smells, the formats,
 * the cache, or the CLI -- none of which have any reason to change because a compiler moved a
 * class.
 *
 * <h2>Implementing one</h2>
 *
 * <p>Implementations must be instantiable with a public no-argument constructor and named in
 * {@link Adapters}. They are loaded reflectively by name, which is what keeps them off the
 * class path of the phase that has no compiler: an adapter is only ever loaded in a JVM that
 * already has the compiler it was written for.
 */
public interface CompilerModel {

    /**
     * This SDK's own version, which is not yet used for anything.
     *
     * <p>It is here because it will be needed and is cheap now: the moment an adapter ships
     * separately from this jar -- built by someone else, for a Flix generation this build has
     * never seen -- the two need a way to say whether they agree. While every adapter is
     * compiled in the same module, they cannot disagree, so nothing checks it.
     *
     * <p>Today's scope is deliberately narrower than that: <b>Flix 0.75 and up</b>, one
     * adapter, in-tree. Versioning the SDK before there is a second party to version against
     * would be ceremony; leaving no version at all would make the first one a breaking change.
     */
    int SDK_VERSION = 1;

    /**
     * What this adapter was written against, for a diagnostic rather than for dispatch.
     *
     * <p>Selection is by whether the adapter links, not by comparing this string: a fork
     * reporting an unfamiliar version may still have the AST the adapter needs, and a
     * compiler reporting a familiar one may not.
     */
    String targets();

    /**
     * Measures one project, or throws.
     *
     * @param projectRoot the root flixw resolved; only declarations under it are counted, since
     *     a typed root also holds the standard library and every dependency
     * @throws ModelFailure when the project cannot be typed, which is a fact about the project
     *     and not about this adapter
     */
    Counts measure(Path projectRoot) throws ModelFailure;

    /**
     * Everything an adapter reports, as plain numbers.
     *
     * <p>Deliberately flat and deliberately not a compiler type. A richer shape -- handing back
     * declarations, or an AST cursor -- would put compiler concepts back into the callers, which
     * is the coupling this whole interface exists to prevent.
     *
     * <p>Adding a field here is a breaking change for adapters and is meant to feel like one:
     * every supported compiler has to be able to answer it, or say it cannot.
     */
    record Counts(int modules, int definitions, int localDefinitions, int effectfulDefinitions,
                  int branches, int traits, int instances, int enums, int structs, int effects,
                  int typeAliases) { }

    /** A project this adapter could load but could not measure. */
    class ModelFailure extends Exception {
        private static final long serialVersionUID = 1L;

        public ModelFailure(String message) {
            super(message);
        }
    }
}
