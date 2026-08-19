package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.ModuleInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a measurement becomes a finding.
 *
 * <p>Every number here is arbitrary in the way every such number is. What matters is that each
 * is stated once, in one place, with the reason next to it — and that crossing one is
 * <em>reported</em>, never enforced. This plugin measures and names; it does not fail anybody's
 * build, because the project that needs a 200-line definition exists and its author knows why.
 *
 * <p><b>Tests are judged differently, not exempted.</b> A test with no doc comment is not a gap
 * in a public API; a test that is long is usually a table of cases, which is the clearest way to
 * write it. Reporting those trains a reader to skim the whole list, and then the finding that
 * mattered goes with them.
 */
final class Thresholds {
    private Thresholds() { }

    /** Beyond this many lines a definition is hard to hold in the head at once. */
    static final int MAX_LINES = 60;

    /**
     * Counting the widest parameter list anywhere inside, not the outer signature.
     *
     * <p>A definition threading eight accumulators through a local loop reads as taking two, and
     * the outer signature is exactly the thing that hides it.
     */
    static final int MAX_PARAMETERS = 5;

    /** Branches inside branches inside branches; the fourth is where following it stops. */
    static final int MAX_NESTING = 4;

    /**
     * Complexity per line, which is the measure that separates length from density.
     *
     * <p>A hundred readable lines and five dense ones can score the same in total. The second is
     * the one worth looking at, and a total alone calls it the better of the two.
     */
    static final double MAX_COGNITIVE_DENSITY = 1.0;

    /** A module depending on this many others is hard to move and hard to test. */
    static final int MAX_FAN_OUT = 12;

    /**
     * Tokens on one line, which catches what a column count cannot.
     *
     * <p>A line of 90 columns made of long descriptive names is easier to read than one of 70
     * made of punctuation and one-letter binders. Length measures how far the eye travels;
     * this measures how much there is to take in.
     */
    static final int MAX_LINE_TOKENS = 30;

    static List<SourceMetrics.Smell> apply(List<DefInfo> defs, List<ModuleInfo> modules) {
        List<SourceMetrics.Smell> out = new ArrayList<>();
        for (DefInfo d : defs) {
            if (!d.isTest() && d.lines() > MAX_LINES)
                out.add(smell("definition-too-long", d,
                    d.lines() + " lines, over " + MAX_LINES));
            if (d.widestParameterList() > MAX_PARAMETERS)
                out.add(smell("too-many-parameters", d,
                    d.widestParameterList() + " parameters, over " + MAX_PARAMETERS
                        + (d.maxLocalParameters() > d.parameters()
                           ? " (widest is a local definition, not the signature)" : "")));
            if (d.nesting() > MAX_NESTING)
                out.add(smell("deeply-nested", d, d.nesting() + " levels, over " + MAX_NESTING));
            if (d.cognitiveDensity() > MAX_COGNITIVE_DENSITY && d.lines() > 3)
                out.add(smell("dense", d, String.format("%.1f complexity per line, over %.1f",
                    d.cognitiveDensity(), MAX_COGNITIVE_DENSITY)));
            // Against the local that owns the line, not the definition it sits in: a crammed
            // line blamed on a long outer definition sends the reader to the wrong place.
            if (d.maxLineTokens() > MAX_LINE_TOKENS)
                out.add(new SourceMetrics.Smell("crammed-line", d.file(), d.maxLineTokensLine(),
                    d.maxLineTokens() + " tokens on one line, over " + MAX_LINE_TOKENS
                        + "  [" + d.maxLineTokensOwner() + "]"));
            // Public, not a test, and nobody wrote down what it is for. The one finding here
            // that is about the reader rather than the writer.
            if (d.isPublic() && !d.isTest() && !d.hasDoc())
                out.add(smell("undocumented-public", d, "public with no doc comment"));
        }
        for (ModuleInfo m : modules) {
            if (m.fanOut() > MAX_FAN_OUT)
                out.add(new SourceMetrics.Smell("wide-coupling", m.name(), 0,
                    m.fanOut() + " modules depended on, over " + MAX_FAN_OUT));
        }
        return out;
    }

    private static SourceMetrics.Smell smell(String rule, DefInfo d, String detail) {
        return new SourceMetrics.Smell(rule, d.file(), d.line(), detail + "  [" + d.name() + "]");
    }
}
