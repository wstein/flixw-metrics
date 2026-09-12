package dev.flixw.metrics;

import java.util.HashSet;

/** Checks that every public rule has one complete, stable definition. */
public final class RuleDefinitionsTest {
    private RuleDefinitionsTest() { }

    public static void main(String[] args) {
        var rules = RuleDefinitions.all();
        require(rules.size() == 9, "the public rule set stays explicit");
        require(new HashSet<>(rules.stream().map(RuleDefinitions.Rule::id).toList()).size()
                == rules.size(), "rule ids are unique");
        for (RuleDefinitions.Rule rule : rules) {
            require(!rule.id().isBlank() && !rule.title().isBlank()
                    && !rule.category().isBlank() && !rule.description().isBlank()
                    && !rule.advice().isBlank() && !rule.level().isBlank(),
                "every rule carries complete consumer metadata: " + rule.id());
            require(rule.categorical() || rule.defaultLimit() > 0,
                "numeric rules carry a positive default limit: " + rule.id());
            require(RuleDefinitions.byId(rule.id()) == rule,
                "a stable id resolves to its definition: " + rule.id());
        }
        String sarif = Formats.sarif(FormatsTest.reportForOtherTests(), null);
        for (RuleDefinitions.Rule rule : rules)
            require(sarif.contains("\"id\": \"" + rule.id() + "\""),
                "SARIF declares registry rule " + rule.id());
        System.out.println("RuleDefinitionsTest: ok");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
