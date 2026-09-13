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
 * <em>reported</em> by default. A caller may explicitly opt into a severity gate, because the
 * project that needs a 200-line definition exists and its author knows why.
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
        String portable = file.replace('\\', '/');
        return portable.startsWith("test/") || portable.contains("/test/");
    }

    static List<SourceMetrics.Smell> apply(List<DefInfo> defs, List<ModuleInfo> modules) {
        return apply(defs, modules, MetricsConfig.defaults());
    }

    static List<SourceMetrics.Smell> apply(List<DefInfo> defs, List<ModuleInfo> modules,
                                           MetricsConfig config) {
        List<SourceMetrics.Smell> out = new ArrayList<>();
        for (DefInfo d : defs) {
            double maxLines = config.limit(RuleDefinitions.DEFINITION_TOO_LONG);
            if (config.enabled(RuleDefinitions.DEFINITION_TOO_LONG)
                    && !d.isTest() && d.lines() > maxLines)
                out.add(at(d, "definition-too-long", d.lines(), maxLines, "lines", ""));
            double maxParameters = config.limit(RuleDefinitions.TOO_MANY_PARAMETERS);
            if (config.enabled(RuleDefinitions.TOO_MANY_PARAMETERS)
                    && d.widestParameterList() > maxParameters) {
                boolean local = d.maxLocalParameters() > d.parameters();
                out.add(local
                    ? new SourceMetrics.Smell("too-many-parameters",
                        d.maxLocalParametersOwner(), d.file(), d.maxLocalParametersLine(),
                        d.maxLocalParameters(), maxParameters,
                        "local definition", "parameters")
                    : at(d, "too-many-parameters", d.parameters(), maxParameters,
                        "parameters", ""));
            }
            boolean documentedApi = d.isPublic() && !d.isTest() && !inTests(d.file());
            double maxFlixdocParameters = config.limit(RuleDefinitions.NOISY_FLIXDOC_PARAMETERS);
            if (config.enabled(RuleDefinitions.NOISY_FLIXDOC_PARAMETERS) && documentedApi
                    && d.flixdocParameterCharacters() > maxFlixdocParameters)
                out.add(at(d, "noisy-flixdoc-parameters", d.flixdocParameterCharacters(),
                    maxFlixdocParameters, "rendered characters", "formal-parameter span only"));
            DocumentationMetrics.ParameterDocs parameterDocs =
                DocumentationMetrics.parameters(d.formalParameterNames(), d.docText());
            if (config.enabled(RuleDefinitions.REDUNDANT_PARAMETER_DOC) && documentedApi
                    && parameterDocs.redundantEntries() >= 2)
                out.add(at(d, "redundant-parameter-doc", 1, 1, "",
                    parameterDocs.redundantEntries() + " of " + parameterDocs.entries()
                        + " recognized parameter entries add only boilerplate"));
            double maxReturn = config.limit(RuleDefinitions.WIDE_RETURN);
            if (config.enabled(RuleDefinitions.WIDE_RETURN) && d.returnWidth() > maxReturn)
                out.add(at(d, "wide-return", d.returnWidth(), maxReturn, "parts", ""));
            double maxNesting = config.limit(RuleDefinitions.DEEPLY_NESTED);
            if (config.enabled(RuleDefinitions.DEEPLY_NESTED) && d.nesting() > maxNesting)
                out.add(at(d, "deeply-nested", d.nesting(), maxNesting, "levels", ""));
            double maxDensity = config.limit(RuleDefinitions.DENSE);
            if (config.enabled(RuleDefinitions.DENSE)
                    && d.cognitiveDensity() > maxDensity
                    && d.codeLines() >= RuleDefinitions.DENSE.minimumCodeLines())
                out.add(at(d, "dense", d.cognitiveDensity(), maxDensity,
                    "complexity per line", ""));
            // Against the local that owns the line, not the definition it sits in: a crammed
            // line blamed on a long outer definition sends the reader to the wrong place. That
            // is why the subject is the owner while the location is the line itself.
            double maxTokens = config.limit(RuleDefinitions.CRAMMED_LINE);
            if (config.enabled(RuleDefinitions.CRAMMED_LINE) && d.maxLineTokens() > maxTokens)
                out.add(new SourceMetrics.Smell("crammed-line", d.maxLineTokensOwner(), d.file(),
                    d.maxLineTokensLine(), d.maxLineTokens(), maxTokens, "", "tokens"));
            // Public, not a test, and nobody wrote down what it is for. The one finding here
            // that is about the reader rather than the writer.
            //
            // Categorical, not a magnitude: something is absent, and there is no amount by which
            // it is absent. It carries no unit, which is how the schema says so.
            // Exempt by location as well as by annotation. `isTest` is the @Test annotation,
            // so a `pub` helper in test/ -- public for visibility from the test that uses it,
            // not because anyone outside will call it -- was reported as undocumented API and
            // counted against documentation coverage. The scope is stated in the report.
            if (config.enabled(RuleDefinitions.UNDOCUMENTED_PUBLIC)
                    && d.isPublic() && !d.isTest() && !inTests(d.file()) && !d.hasDoc())
                out.add(at(d, "undocumented-public", 1, 1, "", "public with no doc comment"));
        }
        for (ModuleInfo m : modules) {
            double maxFanOut = config.limit(RuleDefinitions.WIDE_COUPLING);
            if (config.enabled(RuleDefinitions.WIDE_COUPLING) && m.fanOut() > maxFanOut)
                // A module has no file of its own; it spans them by definition.
                out.add(new SourceMetrics.Smell("wide-coupling", m.name(), "", 0,
                    m.fanOut(), maxFanOut, "", RuleDefinitions.WIDE_COUPLING.unit()));
        }
        return out;
    }

    private static SourceMetrics.Smell at(DefInfo d, String rule, double actual, double limit,
                                          String unit, String note) {
        return new SourceMetrics.Smell(rule, d.name(), d.file(), d.line(), actual, limit, note,
            unit);
    }
}
