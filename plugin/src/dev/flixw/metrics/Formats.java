package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The renderings that are for somebody other than the person who typed the
 * command.
 *
 * <p>
 * Text is for a terminal and JSON is for a program; these two are for a pull
 * request and for a
 * code-scanning tab. Both are derived from the same {@link Metrics.Report}, so
 * a cached run can
 * serve any of the four — which is why the report carries its rankings and
 * findings as data
 * rather than as text.
 */
final class Formats {
    private Formats() {
    }

    /**
     * Markdown, ordered as a work plan rather than as a data dump.
     *
     * <p>
     * Findings first and grouped by rule, because the question a reader arrives
     * with is "what
     * should I do", not "how many definitions are there". The totals go last, where
     * they read as
     * context for the findings instead of as a wall to get past before reaching
     * them.
     */
    /**
     * How many of one rule's findings are listed before the rest become a count.
     */
    private static final int SHOWN_PER_RULE = 10;

    static String markdown(Metrics.Report r, Provenance p) {
        StringBuilder b = new StringBuilder("# Flix metrics\n\n");
        if (p != null) {
            b.append("| | |\n|---|---|\n");
            b.append("| commit | `").append(p.commit()).append(p.dirty() ? "` **+ uncommitted changes**" : "`")
                    .append(" |\n");
            b.append("| analyzer | metrics ").append(p.version()).append(" |\n");
            b.append("| measured | ").append(p.when()).append(" |\n");
            b.append("| thresholds | lines ").append(Thresholds.MAX_LINES)
                    .append(", params ").append(Thresholds.MAX_PARAMETERS)
                    .append(", nesting ").append(Thresholds.MAX_NESTING)
                    .append(", fan-out ").append(Thresholds.MAX_FAN_OUT)
                    .append(", tokens/line ").append(Thresholds.MAX_LINE_TOKENS).append(" |\n\n");
            if (p.dirty())
                b.append("> Measured over a working tree with uncommitted changes, so this"
                        + " describes a state no commit contains. Not a baseline.\n\n");
        }

        if (r.smells().isEmpty()) {
            b.append("No findings.\n\n");
        } else {
            // Grouped, because ten instances of one rule is one decision to make while ten
            // separate rules is ten, and an ungrouped list hides which it is.
            //
            // Ordered by the worst instance in each group, not by how many there are:
            // sixteen
            // definitions one doc comment short is a chore, and one definition at four
            // times the
            // nesting limit is a problem. Counting alone puts the chore first every time.
            Map<String, List<SourceMetrics.Smell>> byRule = new LinkedHashMap<>();
            r.smells().stream()
                    .collect(java.util.stream.Collectors.groupingBy(SourceMetrics.Smell::rule))
                    .entrySet().stream()
                    .sorted((x, y) -> Double.compare(worst(y.getValue()), worst(x.getValue())))
                    .forEach(e -> byRule.put(e.getKey(),
                            e.getValue().stream()
                                    .sorted((a, c) -> Double.compare(c.overBy(), a.overBy())).toList()));

            // Production first and tests second, in their own sections rather than
            // interleaved. Test code is written to different rules -- a table of cases is
            // long on purpose -- so letting it compete for the top of one list buries the
            // findings someone is actually going to act on.
            b.append("## Findings (").append(r.smells().size()).append(")\n\n");
            findings(b, byRule, false);
            findings(b, byRule, true);
        }

        // What is actually in the unnamed module. Naming it turned a blank row into a
        // question; this answers it, because "the root module is the most coupled thing
        // here"
        // is only actionable once you can see whether that is entry-point glue or
        // domain code
        // that never got a `mod`.
        // Production only, for the same reason the priority table is: a test is almost
        // never
        // inside a `mod`, so listing them all makes the root module look like a test
        // problem
        // and hides the domain code that is the actual question.
        List<CompilerModel.DefInfo> rootAll = r.defs().stream()
                .filter(d -> "(root)".equals(d.module())).toList();
        List<CompilerModel.DefInfo> rootDefs = rootAll.stream()
                .filter(d -> !Thresholds.inTests(d.file())).toList();
        if (!rootDefs.isEmpty()) {
            b.append("## The `(root)` module\n\n");
            b.append(rootDefs.size()).append(rootDefs.size() == 1 ? " definition sits" : " definitions sit")
                    .append(" outside any `mod` block. Entry-point glue is fine here; domain code"
                            + " belongs in a module.");
            if (rootAll.size() > rootDefs.size())
                b.append(" (").append(rootAll.size() - rootDefs.size())
                        .append(" more are tests, which are rarely in one.)");
            b.append("\n\n");
            b.append("| definition | at |\n|---|---|\n");
            for (CompilerModel.DefInfo d : rootDefs.stream()
                    .sorted((x, y) -> Integer.compare(y.lines(), x.lines()))
                    .limit(SHOWN_PER_RULE).toList())
                b.append("| `").append(d.name()).append("` | `").append(d.file())
                        .append(':').append(d.line()).append("` |\n");
            if (rootDefs.size() > SHOWN_PER_RULE)
                b.append("| _… and ").append(rootDefs.size() - SHOWN_PER_RULE)
                        .append(" more_ | |\n");
            b.append('\n');
        }

        // Every derived measure, with its formula and its direction. A number without
        // one is
        // not reviewable: "instability 0.97" tells a reader nothing about whether to
        // act, and
        // nobody should have to read the analyzer to find out.
        b.append("## What the measures mean\n\n");
        b.append("| measure | formula | direction |\n|---|---|---|\n");
        b.append("| `cognitive` | +1 per branch, `match` rule, or loop, **times its nesting depth**"
                + " | higher is worse |\n");
        b.append("| `dense` | `cognitive / lines` of one definition | higher is worse;"
                + " flagged over 1.0 |\n");
        b.append("| `crammed` | most lexer tokens on any one line of a definition | higher is"
                + " worse; flagged over ").append(Thresholds.MAX_LINE_TOKENS).append(" |\n");
        b.append("| `instability` | `fan-out / (fan-out + fan-in)` of a module | 0 is depended"
                + " upon and stable, 1 depends on others and is free to change |\n");
        b.append("| `docCoveragePercent` | documented ÷ public definitions | higher is better;"
                + " `@Test` functions and anything under `test/` are excluded |\n");
        b.append("| `tests` | definitions carrying `@Test`, discovered — not executed |\n\n");

        if (!r.ranks().isEmpty()) {
            // Production only. This table is the one thing in the report that says what to
            // do
            // next, and a test ranking above a renderer because a table of cases is long is
            // not a suggestion anyone should follow. The test entries are in `--format
            // json`.
            List<Rankings.Rank> shown = r.ranks().stream()
                    .filter(k -> !k.file().startsWith("test/") && !k.file().contains("/test/")).toList();
            int hidden = r.ranks().size() - shown.size();
            // "Where to look first" is a call to action, and there is nothing to act on
            // when
            // nothing crossed a threshold above -- these are just the extremes of each
            // measure,
            // which exist whether or not they are a problem. Say that plainly instead of
            // reusing the actionable heading for a report that just said "No findings."
            b.append("## Where each measure peaks\n\n");
            if (r.smells().isEmpty())
                b.append("Nothing above crossed a threshold, so there is nothing to act on."
                        + " This is simply the current extreme of each measure.\n\n");

            if (hidden > 0)
                b.append("_Production code only; ").append(hidden)
                        .append(" test entries omitted and kept in `--format json`._\n\n");
            b.append("| measure | subject | value | at |\n");
            b.append("|---|---|---|---|\n");
            for (Rankings.Rank k : shown) {
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

    /** The worst instance in a group, which is what decides the group's place. */
    private static double worst(List<SourceMetrics.Smell> smells) {
        return smells.stream().mapToDouble(SourceMetrics.Smell::overBy).max().orElse(0);
    }

    /**
     * What to do about a rule, in one line.
     *
     * <p>
     * A finding without an action is a complaint. These are deliberately short and
     * deliberately not prescriptive about *how* — the reader knows their code and
     * this does not.
     */
    private static String advice(String rule) {
        return switch (rule) {
            case "definition-too-long" -> "Split it, or name the parts by extracting local definitions.";
            case "too-many-parameters" -> "Group related parameters into a record, or thread less state.";
            case "wide-return" -> "Name the shape: a record with a type alias reads better than a wide tuple.";
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
     * SARIF 2.1.0, so findings land inline on a diff instead of in a log nobody
     * opens.
     *
     * <p>
     * Hand-written rather than through a library. The document is small, its shape
     * is fixed by
     * a published schema, and a JSON dependency on the class path this plugin
     * shares with a
     * compiler is a cost with no matching benefit — the same reasoning that keeps
     * the report's own
     * JSON hand-written.
     *
     * <p>
     * Every rule is declared in {@code rules} even when nothing triggered it, so a
     * consumer
     * that builds its UI from the tool's descriptor does not show a different set
     * of rules on
     * every run depending on what happened to fire.
     */
    /**
     * Is this finding in test code? By path, which is the only thing a finding
     * carries.
     */
    private static boolean isTest(SourceMetrics.Smell s) {
        String w = s.where();
        return w.startsWith("test/") || w.startsWith("test\\") || w.contains("/test/");
    }

    /**
     * One half of the findings, grouped by rule.
     *
     * <p>
     * Capped per rule, because 179 line-length warnings is not 179 decisions -- it
     * is one,
     * about a threshold, and printing them all buries every other rule. The count
     * stays exact
     * and the full list stays in `--format json`, which is where a tool would read
     * it anyway.
     */
    private static void findings(StringBuilder b,
            Map<String, List<SourceMetrics.Smell>> byRule, boolean tests) {
        boolean any = byRule.values().stream().flatMap(List::stream)
                .anyMatch(s -> isTest(s) == tests);
        if (!any)
            return;
        b.append("## ").append(tests ? "In tests" : "In production code").append("\n\n");
        for (Map.Entry<String, List<SourceMetrics.Smell>> e : byRule.entrySet()) {
            List<SourceMetrics.Smell> half = e.getValue().stream().filter(s -> isTest(s) == tests).toList();
            if (half.isEmpty())
                continue;
            b.append("### `").append(e.getKey()).append("` — ").append(half.size()).append("\n\n");
            b.append("_").append(advice(e.getKey())).append("_\n\n");
            for (SourceMetrics.Smell s : half.subList(0, Math.min(SHOWN_PER_RULE, half.size()))) {
                b.append("- ");
                if (!s.where().isEmpty())
                    b.append('`').append(s.where()).append("` ");
                b.append('`').append(s.subject()).append("` — ").append(s.detail());
                // The multiple, so a reader can see at a glance which of sixteen findings is
                // the one actually worth opening.
                if (s.overBy() > 1)
                    b.append(String.format("  _(%.1fx)_", s.overBy()));
                b.append('\n');
            }
            if (half.size() > SHOWN_PER_RULE)
                b.append("- _… and ").append(half.size() - SHOWN_PER_RULE)
                        .append(" more; `--format json` has every one_\n");
            b.append('\n');
        }
    }

    static String sarif(Metrics.Report r) {
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"$schema\": \"https://json.schemastore.org/sarif-2.1.0.json\",\n");
        b.append("  \"version\": \"2.1.0\",\n  \"runs\": [\n    {\n");
        b.append("      \"tool\": {\n        \"driver\": {\n");
        b.append("          \"name\": \"metrics\",\n");
        b.append("          \"informationUri\": \"https://github.com/wstein/flixw-metrics\",\n");
        b.append("          \"rules\": [\n");
        List<String> rules = List.of("definition-too-long", "too-many-parameters", "wide-return",
                "deeply-nested", "dense", "crammed-line", "line-too-long", "undocumented-public",
                "wide-coupling");
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
            b.append(", \"message\": {\"text\": ")
                    .append(SourceMetrics.Smell.quote(s.subject() + ": " + s.detail()));
            b.append("}, \"locations\": [{\"physicalLocation\": {");
            b.append("\"artifactLocation\": {\"uri\": ").append(SourceMetrics.Smell.quote(s.file()));
            // A module-level finding has no file. SARIF requires a region's line to be >=
            // 1, so
            // one that has no line is reported without a region rather than with a made-up
            // line 0.
            b.append("}");
            if (s.line() > 0)
                b.append(", \"region\": {\"startLine\": ").append(s.line()).append("}");
            b.append("}}]}");
            b.append(i == r.smells().size() - 1 ? "\n" : ",\n");
        }
        b.append("      ]\n    }\n  ]\n}\n");
        return b.toString();
    }

    /**
     * `definition-too-long` reads as a rule id; `DefinitionTooLong` reads as a rule
     * name.
     */
    private static String camel(String rule) {
        StringBuilder b = new StringBuilder();
        for (String part : rule.split("-")) {
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }
}
