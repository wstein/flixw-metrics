package dev.flixw.metrics;

import java.util.regex.Pattern;

/** Selects rendered findings without changing measurements, comparisons, or quality gates. */
record PresentationFilter(String rule, String minimumSeverity, String fileGlob,
                          Pattern filePattern) {
    static PresentationFilter none() { return new PresentationFilter(null, null, null, null); }

    static PresentationFilter of(String rule, String minimumSeverity, String fileGlob) {
        if (rule != null) {
            try {
                RuleDefinitions.byId(rule);
            } catch (IllegalArgumentException e) {
                throw new Main.Usage("unknown rule " + rule);
            }
        }
        if (minimumSeverity != null
                && !java.util.List.of("note", "warning", "error").contains(minimumSeverity))
            throw new Main.Usage("unknown severity " + minimumSeverity
                + " (expected note, warning or error)");
        return new PresentationFilter(rule, minimumSeverity, fileGlob,
            fileGlob == null ? null : MetricsConfig.glob(fileGlob));
    }

    boolean active() { return rule != null || minimumSeverity != null || fileGlob != null; }

    String json() {
        return "{\"rule\": " + nullable(rule)
            + ", \"minimumSeverity\": " + nullable(minimumSeverity)
            + ", \"file\": " + nullable(fileGlob) + "}";
    }

    boolean matches(SourceMetrics.Smell finding) {
        return (rule == null || rule.equals(finding.rule()))
            && (minimumSeverity == null || RuleDefinitions.levelRank(
                RuleDefinitions.byId(finding.rule()).level())
                >= RuleDefinitions.levelRank(minimumSeverity))
            && (filePattern == null || filePattern.matcher(
                finding.file().replace('\\', '/')).matches());
    }

    private static String nullable(String value) {
        return value == null ? "null" : SourceMetrics.Smell.quote(value);
    }
}
