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
 * crossing it is a string, a number or a boolean. One implementation per compatible Flix family
 * sits behind it, written against that family's oldest verified AST, and nothing outside those
 * implementations imports {@code ca.uwaterloo.flix}.
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
     * never seen -- the two need a way to say whether they agree. Today each adapter is compiled
     * in its own versioned module under the top-level {@code adapter} group and every adapter is
     * packaged with this SDK in the same plugin jar, so they cannot disagree and nothing checks
     * the declared version.
     *
     * <p>The verified scope is <b>Flix 0.60.0, 0.61.0, 0.65.0, 0.66.1, 0.67.1, 0.67.2,
     * 0.68.0, 0.75.2, 0.75.3, and 0.76.0</b> across six in-tree adapter families. Versioning
     * the SDK before there is a second party to version against would be ceremony; leaving no
     * version at all would make the first one a breaking change.
     */
    int SDK_VERSION = 1;

    /**
     * Measures one project, or throws.
     *
     * <p>Throws {@code ModelFailure} when the project cannot be typed, which is a fact about the
     * project and not about this adapter. The checked exception remains part of the signature.
     *
     * @param projectRoot the root flixw resolved; only declarations under it are described, since
     *     a typed root also holds the standard library and every dependency
     */
    Model measure(Path projectRoot) throws ModelFailure;

    /**
     * One definition, as the function it is.
     *
     * @param module the namespace it is declared in, from its own symbol
     * @param file relative to the project root, so a report does not carry someone's home
     *     directory and two machines produce the same bytes
     * @param lines how many lines the definition spans
     * @param codeLines how many lines in that span carry lexer-confirmed code
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
     * @param returnWidth how many parts the returned value has -- a tuple's arity, or a
     *     record's top-level field count. A record of ten fields is a parameter list in the other
     *     direction: wide for the same reason, read for the same reason, and invisible to every
     *     other measure here
     * @param effects the declared effects, empty when pure
     * @param flixdocParameterCharacters Unicode code points in FlixDoc's rendered formal-parameter
     *     span, or zero
     *     for a nullary definition
     * @param formalParameterNames outer formal names in source order, excluding nullary Unit
     * @param docText the declaration's raw Markdown documentation, empty when undocumented
     * @param datalogDependencies distinct relational predicates referenced from constraint bodies,
     *     sorted by name; facts and functional or guard body terms do not add dependencies
     * @param datalogDependencyDepth predicate levels in the longest dependency path after
     *     recursive components are collapsed, or zero when no relational dependency edge exists
     * @param recursiveDatalogPredicates predicates participating in a self or mutual dependency
     *     cycle, sorted by name
     * @param flixdocResultCharacters Unicode code points in the compiler-rendered result type
     * @param handlers effect-handler literals in this definition
     * @param handledOperations operation clauses across those handlers
     * @param maxHandlerOperations most operation clauses in one handler
     * @param resumptions direct invocations of handler-rule continuation parameters
     * @param effectDetails instantiated effect constructors and their rendered type arguments
     */
    record DefInfo(String name, String module, String file, int line, int lines, int codeLines,
                   int parameters, int maxLocalParameters, String maxLocalParametersOwner,
                   int maxLocalParametersLine, int localDefs, int nesting, int cognitive,
                   int maxLineTokens, int maxLineTokensLine, String maxLineTokensOwner,
                   int datalogRules, int datalogFacts, int returnWidth,
                   boolean isPublic, boolean isTest, boolean hasDoc, List<String> effects,
                   int flixdocParameterCharacters, List<String> formalParameterNames,
                   String docText, List<String> datalogDependencies, int datalogDependencyDepth,
                   List<String> recursiveDatalogPredicates, int flixdocResultCharacters,
                   int handlers, int handledOperations, int maxHandlerOperations,
                   int resumptions, List<EffectDetail> effectDetails) {

        /** One declared capability with compiler-rendered type arguments. */
        public record EffectDetail(String name, List<String> arguments) {
            public EffectDetail {
                arguments = List.copyOf(arguments);
            }

            public int argumentCount() { return arguments.size(); }
        }

        /** Starts a definition whose remaining measurements are assigned by name. */
        public static Builder builder(String name, String module, String file, int line) {
            return new Builder(name, module, file, line);
        }

        /**
         * Named construction prevents adjacent same-typed measurements from silently swapping.
         * Defaults describe an absent measurement, so adapters only assign facts they observed.
         */
        public static final class Builder {
            private final String name;
            private final String module;
            private final String file;
            private final int line;
            private int lines;
            private int codeLines;
            private int parameters;
            private int maxLocalParameters;
            private String maxLocalParametersOwner;
            private int maxLocalParametersLine;
            private int localDefs;
            private int nesting;
            private int cognitive;
            private int maxLineTokens;
            private int maxLineTokensLine;
            private String maxLineTokensOwner;
            private int datalogRules;
            private int datalogFacts;
            private int returnWidth = 1;
            private boolean isPublic;
            private boolean isTest;
            private boolean hasDoc;
            private List<String> effects = List.of();
            private int flixdocParameterCharacters;
            private List<String> formalParameterNames = List.of();
            private String docText = "";
            private List<String> datalogDependencies = List.of();
            private int datalogDependencyDepth;
            private List<String> recursiveDatalogPredicates = List.of();
            private int flixdocResultCharacters;
            private int handlers;
            private int handledOperations;
            private int maxHandlerOperations;
            private int resumptions;
            private List<EffectDetail> effectDetails = List.of();

            private Builder(String name, String module, String file, int line) {
                this.name = name;
                this.module = module;
                this.file = file;
                this.line = line;
                this.maxLineTokensLine = line;
                this.maxLineTokensOwner = name;
                this.maxLocalParametersOwner = name;
                this.maxLocalParametersLine = line;
            }

            public Builder lines(int value) { lines = value; return this; }
            public Builder codeLines(int value) { codeLines = value; return this; }
            public Builder parameters(int value) { parameters = value; return this; }
            public Builder maxLocalParameters(int value) {
                maxLocalParameters = value; return this;
            }
            public Builder maxLocalParametersOwner(String value) {
                maxLocalParametersOwner = value; return this;
            }
            public Builder maxLocalParametersLine(int value) {
                maxLocalParametersLine = value; return this;
            }
            public Builder localDefs(int value) { localDefs = value; return this; }
            public Builder nesting(int value) { nesting = value; return this; }
            public Builder cognitive(int value) { cognitive = value; return this; }
            public Builder maxLineTokens(int value) { maxLineTokens = value; return this; }
            public Builder maxLineTokensLine(int value) {
                maxLineTokensLine = value; return this;
            }
            public Builder maxLineTokensOwner(String value) {
                maxLineTokensOwner = value; return this;
            }
            public Builder datalogRules(int value) { datalogRules = value; return this; }
            public Builder datalogFacts(int value) { datalogFacts = value; return this; }
            public Builder returnWidth(int value) { returnWidth = value; return this; }
            public Builder isPublic(boolean value) { isPublic = value; return this; }
            public Builder isTest(boolean value) { isTest = value; return this; }
            public Builder hasDoc(boolean value) { hasDoc = value; return this; }
            public Builder effects(List<String> value) { effects = List.copyOf(value); return this; }
            public Builder flixdocParameterCharacters(int value) {
                flixdocParameterCharacters = value; return this;
            }
            public Builder formalParameterNames(List<String> value) {
                formalParameterNames = List.copyOf(value); return this;
            }
            public Builder docText(String value) { docText = value; return this; }
            public Builder datalogDependencies(List<String> value) {
                datalogDependencies = List.copyOf(value); return this;
            }
            public Builder datalogDependencyDepth(int value) {
                datalogDependencyDepth = value; return this;
            }
            public Builder recursiveDatalogPredicates(List<String> value) {
                recursiveDatalogPredicates = List.copyOf(value); return this;
            }
            public Builder flixdocResultCharacters(int value) {
                flixdocResultCharacters = value; return this;
            }
            public Builder handlers(int value) { handlers = value; return this; }
            public Builder handledOperations(int value) {
                handledOperations = value; return this;
            }
            public Builder maxHandlerOperations(int value) {
                maxHandlerOperations = value; return this;
            }
            public Builder resumptions(int value) { resumptions = value; return this; }
            public Builder effectDetails(List<EffectDetail> value) {
                effectDetails = List.copyOf(value); return this;
            }

            public DefInfo build() {
                return new DefInfo(name, module, file, line, lines, codeLines, parameters,
                    maxLocalParameters, maxLocalParametersOwner, maxLocalParametersLine,
                    localDefs, nesting, cognitive, maxLineTokens,
                    maxLineTokensLine, maxLineTokensOwner, datalogRules, datalogFacts,
                    returnWidth, isPublic, isTest, hasDoc, effects, flixdocParameterCharacters,
                    formalParameterNames, docText, datalogDependencies, datalogDependencyDepth,
                    recursiveDatalogPredicates, flixdocResultCharacters, handlers,
                    handledOperations, maxHandlerOperations, resumptions, effectDetails);
            }
        }

        /** Complexity per code line; blank or comment-only padding cannot lower it. */
        public double cognitiveDensity() {
            return codeLines == 0 ? 0.0 : (double) cognitive / codeLines;
        }

        public boolean isPure() {
            return effects.isEmpty();
        }

        /** Width of the normalized declared effect set; pure definitions have width zero. */
        public int effectCount() {
            return effects.size();
        }

        /** Number of distinct relational predicates this definition's Datalog rules read. */
        public int datalogDependencyBreadth() {
            return datalogDependencies.size();
        }

        /** Number of predicates participating in Datalog dependency cycles. */
        public int recursiveDatalogPredicateCount() {
            return recursiveDatalogPredicates.size();
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
    record ModuleInfo(String name, int definitions, int lines, int fanIn, int fanOut,
                      List<String> dependencies, List<String> dependents) {

        public ModuleInfo {
            dependencies = dependencies.stream().sorted().distinct().toList();
            dependents = dependents.stream().sorted().distinct().toList();
        }

        public ModuleInfo(String name, int definitions, int lines, int fanIn, int fanOut) {
            this(name, definitions, lines, fanIn, fanOut, List.of(), List.of());
        }

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

    /** Lexer-derived line classes retained per source so report policy can exclude a file. */
    record SourceInfo(String file, LineInfo lines) { }

    /** One compiler-typed operation signature in an effect declaration. */
    record EffectOperationInfo(String name, int arity) { }

    /** One effect declaration and the operation surface it exposes. */
    record EffectInfo(String name, String file, int line, int typeParameters,
                      List<EffectOperationInfo> operations) {
        public EffectInfo {
            operations = List.copyOf(operations);
        }

        /** Number of declared operations. */
        public int operationCount() { return operations.size(); }

        /** Largest compiler-typed operation parameter list, or zero when there are no operations. */
        public int maxOperationArity() {
            return operations.stream().mapToInt(EffectOperationInfo::arity).max().orElse(0);
        }
    }

    /** Everything an adapter reports. Counts that have no per-declaration detail stay counts. */
    record Model(List<DefInfo> defs, List<ModuleInfo> modules, LineInfo lines, int traits,
                 int instances, int enums, int structs, int effects, int typeAliases,
                 List<SourceInfo> sources, List<EffectInfo> effectDeclarations) {
        public Model(List<DefInfo> defs, List<ModuleInfo> modules, LineInfo lines, int traits,
                     int instances, int enums, int structs, int effects, int typeAliases) {
            this(defs, modules, lines, traits, instances, enums, structs, effects, typeAliases,
                List.of(), List.of());
        }

        public Model(List<DefInfo> defs, List<ModuleInfo> modules, LineInfo lines, int traits,
                     int instances, int enums, int structs, int effects, int typeAliases,
                     List<SourceInfo> sources) {
            this(defs, modules, lines, traits, instances, enums, structs, effects, typeAliases,
                sources, List.of());
        }
    }

    /** A project this adapter could load but could not measure. */
    class ModelFailure extends Exception {
        private static final long serialVersionUID = 1L;

        public ModelFailure(String message) {
            super(message);
        }
    }
}
