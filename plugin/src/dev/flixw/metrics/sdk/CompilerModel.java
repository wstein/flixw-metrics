package dev.flixw.metrics.sdk;

import java.nio.file.Path;
import java.util.List;

/**
 * The stable ABI, because {@code flix.jar} does not have one.
 *
 * <p>Flix's internals carry no compatibility promise: {@code Bootstrap}, {@code TypedAst} and
 * everything under them may be reorganised in any release, and have been. A plugin that spreads
 * knowledge of those types through its own code inherits that instability everywhere, and every
 * new compiler becomes an edit in a dozen places.
 *
 * <p>So the instability is confined instead. This interface is the only thing the rest of the
 * plugin knows about a compiler, and it is <b>Java, and versionless on purpose</b>: everything
 * crossing it is a string, a number or a boolean. One implementation per Flix generation sits
 * behind it, written against that generation's AST, and nothing outside those implementations
 * imports {@code ca.uwaterloo.flix}.
 *
 * <p><b>Per declaration, not aggregate.</b> An earlier version returned totals, which was enough
 * to print a summary and useless for anything else: a finding has to name a file and a line, a
 * ranking has to compare definitions to each other, and "the worst three" cannot be recovered
 * from a sum. Returning records costs nothing in coupling — none of them mention a compiler type
 * — and is what lets every smell, format and ranking live outside the adapter.
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
     * @param projectRoot the root flixw resolved; only declarations under it are described, since
     *     a typed root also holds the standard library and every dependency
     * @throws ModelFailure when the project cannot be typed, which is a fact about the project
     *     and not about this adapter
     */
    Model measure(Path projectRoot) throws ModelFailure;

    /**
     * One definition, as the function it is.
     *
     * @param module the namespace it is declared in, from its own symbol
     * @param file relative to the project root, so a report does not carry someone's home
     *     directory and two machines produce the same bytes
     * @param lines how many lines the definition spans
     * @param parameters what the outer signature declares
     * @param maxLocalParameters the widest parameter list of any definition nested inside it --
     *     a body threading eight accumulators through a local loop reads as taking two
     * @param nesting how deeply its branches nest
     * @param cognitive how hard it is to follow: each branch weighted by how many branches
     *     enclose it, so five nested conditions cost more than five consecutive ones
     * @param isTest whether it is annotated {@code @Test}, which changes what is a smell: an
     *     undocumented test is not a gap in a public API
     * @param maxLineTokens the most tokens on any one line this definition spans -- density the
     *     line's <em>length</em> can hide, since a short line of punctuation-heavy code can carry
     *     more to read than a long line of prose-like names
     * @param maxLineTokensOwner the innermost local definition containing that line, or this
     *     definition's own name. A crammed line reported against a 300-line outer definition
     *     sends the reader to the wrong place; the local holding it is where the work is
     * @param datalogRules constraints with a body -- the ones that derive
     * @param datalogFacts constraints with an empty body, which are data written as code and are
     *     not complexity however many there are
     * @param effects the declared effects, empty when pure
     */
    record DefInfo(String name, String module, String file, int line, int lines, int parameters,
                   int maxLocalParameters, int localDefs, int nesting, int cognitive,
                   int maxLineTokens, int maxLineTokensLine, String maxLineTokensOwner,
                   int datalogRules, int datalogFacts,
                   boolean isPublic, boolean isTest, boolean hasDoc, List<String> effects) {

        /** Complexity per line: five dense lines and a hundred readable ones can score alike. */
        public double cognitiveDensity() {
            return lines == 0 ? 0.0 : (double) cognitive / lines;
        }

        public boolean isPure() {
            return effects.isEmpty();
        }

        /** The widest parameter list anywhere inside, outer signature or local. */
        public int widestParameterList() {
            return Math.max(parameters, maxLocalParameters);
        }
    }

    /**
     * A module and what it depends on.
     *
     * @param fanIn how many modules depend on this one
     * @param fanOut how many modules this one depends on
     */
    record ModuleInfo(String name, int definitions, int lines, int fanIn, int fanOut) {

        /**
         * Martin's instability: 0 is depended upon and depends on nothing, 1 is the reverse.
         *
         * <p>A module coupled to nothing has no instability to speak of and is reported as 0
         * rather than as a division by zero.
         */
        public double instability() {
            return fanIn + fanOut == 0 ? 0.0 : (double) fanOut / (fanIn + fanOut);
        }
    }

    /**
     * How the lines of the project divide up.
     *
     * <p>From the compiler's own lexer, not from a scan for {@code //}. A line holding code and a
     * trailing comment is code -- it is a line you have to read as code -- and a line inside a
     * block comment is a comment even though nothing on it says so, which is exactly where
     * counting by hand goes wrong. Doc comments are separated from ordinary ones because they
     * are the documentation, and "how much of this is explained" is a different question from
     * "how much of this is commented out".
     */
    record LineInfo(int total, int code, int comment, int docComment, int blank) { }

    /** Everything an adapter reports. Counts that have no per-declaration detail stay counts. */
    record Model(List<DefInfo> defs, List<ModuleInfo> modules, LineInfo lines, int traits,
                 int instances, int enums, int structs, int effects, int typeAliases) { }

    /** A project this adapter could load but could not measure. */
    class ModelFailure extends Exception {
        private static final long serialVersionUID = 1L;

        public ModelFailure(String message) {
            super(message);
        }
    }
}
