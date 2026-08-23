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

    /** Test code, by path: the one thing a definition carries that says where it lives. */
    static boolean inTests(String file) {
        return file.startsWith("test/") || file.contains("/test/");
    }

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

    /**
     * Parts in a returned value, gated at the same number as parameters.
     *
     * <p>Deliberately the same: returning a six-field record and taking six parameters are the
     * same amount to hold in the head, in opposite directions, and giving them different limits
     * would say one of them is more forgivable than the other.
     */
    static final int MAX_RETURN_WIDTH = MAX_PARAMETERS;

    static List<SourceMetrics.Smell> apply(List<DefInfo> defs, List<ModuleInfo> modules) {
        List<SourceMetrics.Smell> out = new ArrayList<>();
        for (DefInfo d : defs) {
            if (!d.isTest() && d.lines() > MAX_LINES)
                out.add(at(d, "definition-too-long", d.lines(), MAX_LINES, "lines", ""));
            if (d.widestParameterList() > MAX_PARAMETERS)
                out.add(at(d, "too-many-parameters", d.widestParameterList(), MAX_PARAMETERS,
                    "parameters",
                    d.maxLocalParameters() > d.parameters()
                        ? "widest is a local definition, not the signature" : ""));
            if (d.returnWidth() > MAX_RETURN_WIDTH)
                out.add(at(d, "wide-return", d.returnWidth(), MAX_RETURN_WIDTH, "parts", ""));
            if (d.nesting() > MAX_NESTING)
                out.add(at(d, "deeply-nested", d.nesting(), MAX_NESTING, "levels", ""));
            if (d.cognitiveDensity() > MAX_COGNITIVE_DENSITY && d.lines() > 3)
                out.add(at(d, "dense", d.cognitiveDensity(), MAX_COGNITIVE_DENSITY,
                    "complexity per line", ""));
            // Against the local that owns the line, not the definition it sits in: a crammed
            // line blamed on a long outer definition sends the reader to the wrong place. That
            // is why the subject is the owner while the location is the line itself.
            if (d.maxLineTokens() > MAX_LINE_TOKENS)
                out.add(new SourceMetrics.Smell("crammed-line", d.maxLineTokensOwner(), d.file(),
                    d.maxLineTokensLine(), d.maxLineTokens(), MAX_LINE_TOKENS, "", "tokens"));
            // Public, not a test, and nobody wrote down what it is for. The one finding here
            // that is about the reader rather than the writer.
            //
            // Categorical, not a magnitude: something is absent, and there is no amount by which
            // it is absent. It carries no unit, which is how the schema says so.
            // Exempt by location as well as by annotation. `isTest` is the @Test annotation,
            // so a `pub` helper in test/ -- public for visibility from the test that uses it,
            // not because anyone outside will call it -- was reported as undocumented API and
            // counted against documentation coverage. The scope is stated in the report.
            if (d.isPublic() && !d.isTest() && !inTests(d.file()) && !d.hasDoc())
                out.add(at(d, "undocumented-public", 1, 1, "", "public with no doc comment"));
        }
        for (ModuleInfo m : modules) {
            if (m.fanOut() > MAX_FAN_OUT)
                // A module has no file of its own; it spans them by definition.
                out.add(new SourceMetrics.Smell("wide-coupling", m.name(), "", 0,
                    m.fanOut(), MAX_FAN_OUT, "", "modules depended on"));
        }
        return out;
    }

    private static SourceMetrics.Smell at(DefInfo d, String rule, double actual, double limit,
                                          String unit, String note) {
        return new SourceMetrics.Smell(rule, d.name(), d.file(), d.line(), actual, limit, note,
            unit);
    }
}
