package dev.flixw.metrics;

import dev.flixw.metrics.adapter.Flix0680Adapter;
import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.Model;

import java.nio.file.Path;

/** Exercises the oldest adapter family against its oldest supported real compiler. */
public final class Flix0680AdapterTest {
    private Flix0680AdapterTest() { }

    public static void main(String[] args) throws Exception {
        Path project = Flix0753AdapterTest.copyFixture(Path.of(args[0]));
        try {
            Model model = new Flix0680Adapter().measure(project);
            require(model.defs().size() == 6, "fixture definitions are measured");
            require(model.lines().code() == 21 && model.lines().docComment() == 4,
                "the real lexer classifies fixture lines");
            DefInfo select = definition(model, "Alpha.selectValue");
            require(select.localDefs() == 1 && select.maxLocalParameters() == 2,
                "local definitions cross the older compiler adapter boundary");
            require(select.cognitive() == 2 && select.codeLines() == 4,
                "branches, booleans, and definition code lines are measured");
            DefInfo documented = definition(model, "Alpha.documented");
            require(documented.returnWidth() == 2
                    && documented.flixdocResultCharacters() == 14,
                "typed return shape and its rendered width are measured");
            DefInfo datalog = definition(model, "Alpha.datalog");
            require(datalog.datalogRules() == 3 && datalog.datalogFacts() == 1,
                "Datalog rules and facts cross the older compiler adapter boundary");
            require(datalog.datalogDependencyDepth() == 2
                    && datalog.recursiveDatalogPredicateCount() == 2,
                "Datalog dependency analysis is stable on the older compiler");
            require(definition(model, "testSelectValue").isTest()
                    && definition(model, "testSelectValue").effects()
                        .equals(java.util.List.of("Assert")),
                "compiler test annotations and declared effects are measured");
            require(model.modules().stream().filter(m -> m.name().equals("Beta"))
                    .findFirst().orElseThrow().dependencies().equals(java.util.List.of("Alpha")),
                "resolved module dependencies cross the older compiler adapter boundary");
            // Effect declarations measured before handlers are added: assertEffectMetrics
            // checks the model's total effect count, and HandlerMetrics.flix below declares
            // two effects of its own that would otherwise inflate it.
            Flix0753AdapterTest.writeEffectFixture(project);
            Flix0753AdapterTest.assertEffectMetrics(new Flix0680Adapter().measure(project));
            Flix0753AdapterTest.writeHandlerFixture(project);
            Flix0753AdapterTest.assertHandlerMetrics(new Flix0680Adapter().measure(project));
            System.out.println("Flix0680AdapterTest: ok");
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
