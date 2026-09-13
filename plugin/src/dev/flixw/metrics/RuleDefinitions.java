package dev.flixw.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The single public contract for finding rules, in stable declaration order. */
final class RuleDefinitions {
    private RuleDefinitions() { }

    record Rule(String id, String title, String category, String description, String advice,
                String unit, double defaultLimit, int minimumCodeLines, boolean categorical,
                String level) { }

    static final Rule DEFINITION_TOO_LONG = numeric("definition-too-long", "Definition too long",
        "maintainability", "A non-test definition spans more lines than the configured limit.",
        "Split it, or name the parts by extracting local definitions.", "lines", 60, "warning");
    static final Rule TOO_MANY_PARAMETERS = numeric("too-many-parameters", "Too many parameters",
        "maintainability", "A definition or one of its locals has a wide parameter list.",
        "Group related parameters into a record, or thread less state.", "parameters", 5, "warning");
    static final Rule NOISY_FLIXDOC_PARAMETERS = numeric("noisy-flixdoc-parameters",
        "Noisy FlixDoc parameter list", "documentation",
        "A public function's rendered FlixDoc formal-parameter span exceeds the configured limit.",
        "Name a parameter shape or simplify the public API boundary.", "rendered characters", 140,
        "note");
    static final Rule WIDE_RETURN = numeric("wide-return", "Wide return value", "maintainability",
        "A returned tuple or record has more parts than the configured limit.",
        // Deliberately the parameter limit: these are the same cognitive width in opposite
        // directions, and RuleDefinitionsTest prevents that policy from silently drifting.
        "Name the shape: a record with a type alias reads better than a wide tuple.", "parts", 5,
        "note");
    static final Rule DEEPLY_NESTED = numeric("deeply-nested", "Deeply nested", "complexity",
        "Branch constructs nest more deeply than the configured limit.",
        "Invert a condition to return early, or lift a branch into its own definition.", "levels", 4,
        "warning");
    static final Rule DENSE = numeric("dense", "Dense definition", "complexity",
        "Cognitive complexity per code line exceeds the configured limit. Only definitions with"
            + " at least 4 code lines are eligible.",
        "Simplify the control flow: blank and comment-only lines do not lower this density.",
        "complexity per code line", 1.0, 4, "warning");
    static final Rule CRAMMED_LINE = numeric("crammed-line", "Crammed line", "readability",
        "A source line contains more lexer tokens than the configured limit.",
        "Break the line where it reads, not at a column limit.", "tokens", 35, "note");
    static final Rule LINE_TOO_LONG = numeric("line-too-long", "Line too long", "readability",
        "A source line contains more UTF-16 code units than the configured limit.",
        "Wrap it.", "UTF-16 code units", 100, "note");
    static final Rule UNDOCUMENTED_PUBLIC = categorical("undocumented-public",
        "Undocumented public definition", "documentation",
        "A production public definition has no doc comment.",
        "Say what it is for; it is part of someone else's surface.", "note");
    static final Rule REDUNDANT_PARAMETER_DOC = categorical("redundant-parameter-doc",
        "Redundant parameter documentation", "documentation",
        "At least two formal-parameter list entries repeat names with only boilerplate.",
        "Document constraints, relationships, or behavior; the signature already names and types"
            + " each parameter.", "note");
    static final Rule WIDE_COUPLING = numeric("wide-coupling", "Wide definition-call coupling",
        "coupling", "A module directly calls definitions in more modules than the configured limit.",
        "This module calls many others; consider what it is really responsible for.",
        "modules called", 12, "warning");

    private static final List<Rule> ALL = List.of(DEFINITION_TOO_LONG, TOO_MANY_PARAMETERS,
        NOISY_FLIXDOC_PARAMETERS, WIDE_RETURN, DEEPLY_NESTED, DENSE, CRAMMED_LINE, LINE_TOO_LONG,
        UNDOCUMENTED_PUBLIC, REDUNDANT_PARAMETER_DOC, WIDE_COUPLING);
    private static final Map<String, Rule> BY_ID = index();

    static List<Rule> all() { return ALL; }

    static Rule byId(String id) {
        Rule rule = BY_ID.get(id);
        if (rule == null) throw new IllegalArgumentException("unknown rule: " + id);
        return rule;
    }

    static int levelRank(String level) {
        return switch (level) {
            case "error" -> 3;
            case "warning" -> 2;
            case "note" -> 1;
            default -> 0;
        };
    }

    private static Map<String, Rule> index() {
        Map<String, Rule> out = new LinkedHashMap<>();
        for (Rule rule : ALL)
            if (out.put(rule.id(), rule) != null)
                throw new ExceptionInInitializerError("duplicate rule id: " + rule.id());
        return Map.copyOf(out);
    }

    private static Rule numeric(String id, String title, String category, String description,
                                String advice, String unit, double limit, String level) {
        return numeric(id, title, category, description, advice, unit, limit, 0, level);
    }

    private static Rule numeric(String id, String title, String category, String description,
                                String advice, String unit, double limit, int minimumCodeLines,
                                String level) {
        return new Rule(id, title, category, description, advice, unit, limit, minimumCodeLines,
            false, level);
    }

    private static Rule categorical(String id, String title, String category, String description,
                                    String advice, String level) {
        return new Rule(id, title, category, description, advice, "", 0, 0, true, level);
    }
}
