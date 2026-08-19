package dev.flixw.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The renderings that are for somebody other than the person who typed the command.
 *
 * <p>Text is for a terminal and JSON is for a program; these two are for a pull request and for a
 * code-scanning tab. Both are derived from the same {@link Metrics.Report}, so a cached run can
 * serve any of the four — which is why the report carries its rankings and findings as data
 * rather than as text.
 */
final class Formats {
    private Formats() { }

    /**
     * Markdown, ordered as a work plan rather than as a data dump.
     *
     * <p>Findings first and grouped by rule, because the question a reader arrives with is "what
     * should I do", not "how many definitions are there". The totals go last, where they read as
     * context for the findings instead of as a wall to get past before reaching them.
     */
    static String markdown(Metrics.Report r) {
        StringBuilder b = new StringBuilder("# Flix metrics\n\n");

        if (r.smells().isEmpty()) {
            b.append("No findings.\n\n");
        } else {
            // Grouped, and the biggest group first: ten instances of one rule is one decision to
            // make, while ten separate rules is ten. An ungrouped list hides which it is.
            Map<String, List<SourceMetrics.Smell>> byRule = new LinkedHashMap<>();
            r.smells().stream()
                .collect(java.util.stream.Collectors.groupingBy(SourceMetrics.Smell::rule))
                .entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()))
                .forEach(e -> byRule.put(e.getKey(), e.getValue()));

            b.append("## Findings (").append(r.smells().size()).append(")\n\n");
            for (Map.Entry<String, List<SourceMetrics.Smell>> e : byRule.entrySet()) {
                b.append("### `").append(e.getKey()).append("` — ")
                 .append(e.getValue().size()).append("\n\n");
                b.append("_").append(advice(e.getKey())).append("_\n\n");
                for (SourceMetrics.Smell s : e.getValue()) {
                    b.append("- `").append(s.file()).append(':').append(s.line()).append("` — ")
                     .append(s.detail()).append('\n');
                }
                b.append('\n');
            }
        }

        if (!r.ranks().isEmpty()) {
            b.append("## Where to look first\n\n| measure | subject | value | at |\n");
            b.append("|---|---|---|---|\n");
            for (Rankings.Rank k : r.ranks()) {
                b.append("| ").append(k.measure()).append(" | `").append(k.subject())
                 .append("` | ").append(k.value()).append(" | ")
                 .append(k.file().isEmpty() ? "—" : "`" + k.file() + ":" + k.line() + "`")
                 .append(" |\n");
            }
            b.append('\n');
        }

        b.append("## Totals\n\n| | |\n|---|---:|\n");
        for (String[] pair : r.fieldsForRender()) {
            b.append("| ").append(pair[0]).append(" | ").append(pair[1]).append(" |\n");
        }
        return b.toString();
    }

    /**
     * What to do about a rule, in one line.
     *
     * <p>A finding without an action is a complaint. These are deliberately short and
     * deliberately not prescriptive about *how* — the reader knows their code and this does not.
     */
    private static String advice(String rule) {
        return switch (rule) {
            case "definition-too-long" -> "Split it, or name the parts by extracting local definitions.";
            case "too-many-parameters" -> "Group related parameters into a record, or thread less state.";
            case "deeply-nested" -> "Invert a condition to return early, or lift a branch into its own definition.";
            case "dense" -> "Spread it out: this is complexity per line, so length is not the problem.";
            case "crammed-line" -> "Break the line where it reads, not at a column limit.";
            case "line-too-long" -> "Wrap it.";
            case "undocumented-public" -> "Say what it is for; it is part of someone else's surface.";
            case "wide-coupling" -> "This module reaches into many others; consider what it is really responsible for.";
            default -> "No guidance recorded for this rule.";
        };
    }

    /**
     * SARIF 2.1.0, so findings land inline on a diff instead of in a log nobody opens.
     *
     * <p>Hand-written rather than through a library. The document is small, its shape is fixed by
     * a published schema, and a JSON dependency on the class path this plugin shares with a
     * compiler is a cost with no matching benefit — the same reasoning that keeps the report's own
     * JSON hand-written.
     *
     * <p>Every rule is declared in {@code rules} even when nothing triggered it, so a consumer
     * that builds its UI from the tool's descriptor does not show a different set of rules on
     * every run depending on what happened to fire.
     */
    static String sarif(Metrics.Report r) {
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"$schema\": \"https://json.schemastore.org/sarif-2.1.0.json\",\n");
        b.append("  \"version\": \"2.1.0\",\n  \"runs\": [\n    {\n");
        b.append("      \"tool\": {\n        \"driver\": {\n");
        b.append("          \"name\": \"flixw-metrics\",\n");
        b.append("          \"informationUri\": \"https://github.com/wstein/flixw\",\n");
        b.append("          \"rules\": [\n");
        List<String> rules = List.of("definition-too-long", "too-many-parameters", "deeply-nested",
            "dense", "crammed-line", "line-too-long", "undocumented-public", "wide-coupling");
        for (int i = 0; i < rules.size(); i++) {
            String rule = rules.get(i);
            b.append("            {\"id\": ").append(SourceMetrics.Smell.quote(rule));
            b.append(", \"name\": ").append(SourceMetrics.Smell.quote(camel(rule)));
            b.append(", \"shortDescription\": {\"text\": ")
             .append(SourceMetrics.Smell.quote(advice(rule))).append("}");
            b.append(", \"defaultConfiguration\": {\"level\": \"note\"}}");
            b.append(i == rules.size() - 1 ? "\n" : ",\n");
        }
        b.append("          ]\n        }\n      },\n");
        b.append("      \"results\": [\n");
        for (int i = 0; i < r.smells().size(); i++) {
            SourceMetrics.Smell s = r.smells().get(i);
            b.append("        {\"ruleId\": ").append(SourceMetrics.Smell.quote(s.rule()));
            b.append(", \"level\": \"note\"");
            b.append(", \"message\": {\"text\": ").append(SourceMetrics.Smell.quote(s.detail()));
            b.append("}, \"locations\": [{\"physicalLocation\": {");
            b.append("\"artifactLocation\": {\"uri\": ").append(SourceMetrics.Smell.quote(s.file()));
            // A module-level finding has no file. SARIF requires a region's line to be >= 1, so
            // one that has no line is reported without a region rather than with a made-up line 0.
            b.append("}");
            if (s.line() > 0) b.append(", \"region\": {\"startLine\": ").append(s.line()).append("}");
            b.append("}}]}");
            b.append(i == r.smells().size() - 1 ? "\n" : ",\n");
        }
        b.append("      ]\n    }\n  ]\n}\n");
        return b.toString();
    }

    /** `definition-too-long` reads as a rule id; `DefinitionTooLong` reads as a rule name. */
    private static String camel(String rule) {
        StringBuilder b = new StringBuilder();
        for (String part : rule.split("-")) {
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }
}
