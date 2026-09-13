package dev.flixw.metrics;

import dev.flixw.metrics.adapter.Flix0600Adapter;
import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.Model;

import java.nio.file.Path;

/** Exercises the pre-0.67.2 adapter against its oldest supported real compiler. */
public final class Flix0600AdapterTest {
    private Flix0600AdapterTest() { }

    public static void main(String[] args) throws Exception {
        Path project = Flix0753AdapterTest.copyFixture(Path.of(args[0]));
        try {
            Model model = new Flix0600Adapter().measure(project);
            Flix0753AdapterTest.assertLegacyEffectDetails(model);
            require(model.defs().size() == 6, "fixture definitions are measured");
            require(model.lines().code() == 21 && model.lines().docComment() == 4,
                "the older token positions preserve lexer-backed line metrics");
            DefInfo select = definition(model, "Alpha.selectValue");
            require(select.line() == 19 && select.localDefs() == 1
                    && select.maxLocalParameters() == 2,
                "older source locations preserve declarations and local definitions");
            require(select.cognitive() == 2 && select.codeLines() == 4,
                "branches, booleans, and definition code lines are measured");
            DefInfo datalog = definition(model, "Alpha.datalog");
            require(datalog.datalogRules() == 3 && datalog.datalogFacts() == 1
                    && datalog.datalogDependencyDepth() == 2
                    && datalog.recursiveDatalogPredicateCount() == 2,
                "Datalog measurements cross the older compiler adapter boundary");
            require(definition(model, "testSelectValue").isTest()
                    && definition(model, "testSelectValue").effects().isEmpty(),
                "the Validation-era compiler test annotation is preserved");
            require(model.modules().stream().filter(m -> m.name().equals("Beta"))
                    .findFirst().orElseThrow().dependencies().equals(java.util.List.of("Alpha")),
                "resolved module dependencies cross the older compiler adapter boundary");
            // Effect declarations measured before handlers are added: assertEffectMetrics
            // checks the model's total effect count, and HandlerMetrics.flix below declares
            // two effects of its own that would otherwise inflate it.
            Flix0753AdapterTest.writeEffectFixture(project);
            Flix0753AdapterTest.assertEffectMetrics(new Flix0600Adapter().measure(project));
            Flix0753AdapterTest.writeHandlerFixture(project);
            Flix0753AdapterTest.assertHandlerMetrics(new Flix0600Adapter().measure(project));
            System.out.println("Flix0600AdapterTest: ok");
        } finally {
            Flix0753AdapterTest.delete(project);
        }
    }

    private static DefInfo definition(Model model, String name) {
        return model.defs().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
