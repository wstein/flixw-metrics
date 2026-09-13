package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.ModuleInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The worst few of each measure, which is the part a total cannot give you.
 *
 * <p>"1,842 lines across 240 definitions" tells nobody where to start. "The longest is
 * {@code Json.parse} at 310 lines, and the densest is a local inside it" is somewhere to go on a
 * Tuesday morning. Both come from the same data; only one of them is worth reading.
 *
 * <p>Three of each, because the point is a place to start rather than a backlog. A list of
 * twenty is a report that gets skimmed, and skimming a ranking defeats its only purpose.
 *
 * <p>Rankings are deliberately <em>not</em> findings. A project's longest definition exists
 * whether or not it is too long, and reporting it as a problem would make the threshold list
 * meaningless. {@link Thresholds} says what crossed a line; this says what is furthest along
 * each axis, which is a different question and often has a different answer.
 */
final class Rankings {
    private Rankings() { }

    /** How many of each. See the class comment: a ranking nobody reads ranks nothing. */
    static final int TOP = 3;

    /**
     * One ranked entry.
     *
     * @param measure which ranking this belongs to
     * @param subject the definition or module
     * @param value already formatted, because the unit belongs with the number -- a bare "310"
     *     means nothing three columns away from the word "lines"
     * @param eligible whether structural prerequisites permit a related finding; this does not
     *     say that the value crossed a threshold or that the rule is enabled
     * @param ineligibilityReason empty when eligible, otherwise the prerequisite that was missed
     */
    record Rank(String measure, String subject, String file, int line, String value,
                boolean eligible, String ineligibilityReason) {

        Rank {
            if (eligible != ineligibilityReason.isEmpty())
                throw new IllegalArgumentException(
                    "an eligible rank has no ineligibility reason, and vice versa");
        }

        Rank(String measure, String subject, String file, int line, String value) {
            this(measure, subject, file, line, value, true, "");
        }

        String json() {
            return "{\"measure\": " + SourceMetrics.Smell.quote(measure)
                 + ", \"subject\": " + SourceMetrics.Smell.quote(subject)
                 + ", \"file\": " + SourceMetrics.Smell.quote(file)
                 + ", \"line\": " + line
                 + ", \"value\": " + SourceMetrics.Smell.quote(value)
                 + ", \"eligible\": " + eligible
                 + ", \"ineligibilityReason\": "
                 + SourceMetrics.Smell.quote(ineligibilityReason) + "}";
        }

        String text() {
            return String.format(Locale.ROOT, "  %-18s %-34s %s", measure, value, subject
                + (file.isEmpty() ? "" : "  (" + file + ":" + line + ")")
                + (eligible ? "" : "  [ineligible: " + ineligibilityReason + "]"));
        }
    }

    static List<Rank> of(List<DefInfo> defs, List<ModuleInfo> modules) {
        List<Rank> out = new ArrayList<>();
        // Tests are ranked with everything else here, unlike in Thresholds. A long test is not a
        // defect, but if it is the longest thing in the project that is worth knowing.
        top(out, defs, "longest", DefInfo::lines, d -> d.lines() + " lines",
            d -> d.isTest()
                ? "test definitions are excluded from definition-too-long findings" : "");
        top(out, defs, "densest", DefInfo::cognitiveDensity,
            d -> String.format(Locale.ROOT, "%.1f complexity/line", d.cognitiveDensity()),
            d -> d.codeLines() < RuleDefinitions.DENSE.minimumCodeLines()
                ? "requires at least " + RuleDefinitions.DENSE.minimumCodeLines()
                    + " code lines (has " + d.codeLines() + ")" : "");
        top(out, defs, "most-complex", DefInfo::cognitive, d -> d.cognitive() + " complexity");
        top(out, defs, "deepest", DefInfo::nesting, d -> d.nesting() + " levels nested");
        top(out, defs, "widest", DefInfo::widestParameterList,
            d -> d.widestParameterList() + " parameters");
        top(out, defs, "widest-effect-surface", DefInfo::effectCount,
            d -> d.effectCount() + " declared effect" + (d.effectCount() == 1 ? "" : "s"));
        top(out, defs, "widest-datalog-dependency", DefInfo::datalogDependencyBreadth,
            d -> d.datalogDependencyBreadth() + " body predicate"
                + (d.datalogDependencyBreadth() == 1 ? "" : "s"));
        top(out, defs, "deepest-datalog-dependency", DefInfo::datalogDependencyDepth,
            d -> d.datalogDependencyDepth() + " predicate level"
                + (d.datalogDependencyDepth() == 1 ? "" : "s"));
        top(out, defs, "most-recursive-datalog", DefInfo::recursiveDatalogPredicateCount,
            d -> d.recursiveDatalogPredicateCount() + " recursive predicate"
                + (d.recursiveDatalogPredicateCount() == 1 ? "" : "s"));
        // FlixDoc exposes only the public API. Rank its generated parameter span rather than the
        // source line, which may have been wrapped without making the rendered signature simpler.
        List<DefInfo> documentedApi = defs.stream()
            .filter(d -> d.isPublic() && !d.isTest() && !Thresholds.inTests(d.file())).toList();
        top(out, documentedApi, "longest-flixdoc-parameters",
            DefInfo::flixdocParameterCharacters,
            d -> d.flixdocParameterCharacters() + " rendered characters");
        // Only when it is more than one part; every definition returns something, and a ranking
        // of "returns 1 thing" three times over is noise where a place to look should be.
        top(out, defs, "widest-return", d -> d.returnWidth() > 1 ? d.returnWidth() : 0,
            d -> d.returnWidth() + " parts returned");
        // Reported against the local that owns the line, not the definition it happens to sit in.
        for (DefInfo d : sortedBy(defs, DefInfo::maxLineTokens)) {
            out.add(new Rank("crammed-line", d.maxLineTokensOwner(), d.file(),
                d.maxLineTokensLine(), d.maxLineTokens() + " tokens on one line"));
        }
        // Modules carry no file of their own; a module spans files by definition.
        for (ModuleInfo m : sorted(modules, ModuleInfo::fanOut)) {
            out.add(new Rank("most-coupled", m.name(), "", 0,
                m.fanOut() + " modules called, call-instability "
                    + String.format(Locale.ROOT, "%.2f", m.instability())));
        }
        return out;
    }

    private static <T extends Comparable<T>> void top(List<Rank> out, List<DefInfo> defs,
                                                      String measure,
                                                      Function<DefInfo, T> by,
                                                      Function<DefInfo, String> value) {
        top(out, defs, measure, by, value, ignored -> "");
    }

    private static <T extends Comparable<T>> void top(List<Rank> out, List<DefInfo> defs,
                                                      String measure,
                                                      Function<DefInfo, T> by,
                                                      Function<DefInfo, String> value,
                                                      Function<DefInfo, String> reason) {
        for (DefInfo d : sorted(defs, by)) {
            String why = reason.apply(d);
            out.add(new Rank(measure, d.name(), d.file(), d.line(), value.apply(d),
                why.isEmpty(), why));
        }
    }

    private static List<DefInfo> sortedBy(List<DefInfo> defs, Function<DefInfo, Integer> by) {
        return sorted(defs, by);
    }

    /**
     * The top few, dropping zeroes.
     *
     * <p>A project with no nesting anywhere would otherwise be told its three deepest definitions
     * nest zero levels, which is true, useless, and pushes the entries that mean something off
     * the screen.
     */
    private static <T, K extends Comparable<K>> List<T> sorted(List<T> items, Function<T, K> by) {
        return items.stream()
            .filter(i -> !isZero(by.apply(i)))
            .sorted(Comparator.comparing(by, Comparator.reverseOrder()))
            .limit(TOP)
            .toList();
    }

    private static boolean isZero(Object value) {
        return value instanceof Number n && n.doubleValue() == 0.0;
    }
}
