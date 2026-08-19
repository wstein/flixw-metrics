package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.ModuleInfo;

import java.util.List;

/**
 * Checks that a finding is raised for the right reason, and -- more importantly -- not raised
 * for the wrong one. A rule that fires on every definition trains the reader to skim the list,
 * and then the finding that mattered goes with them.
 */
public final class ThresholdsTest {
    private ThresholdsTest() { }

    public static void main(String[] args) {
        // A test is long because it is a table of cases, and undocumented because a test is not
        // a public API. Neither is a finding.
        DefInfo test = def("Foo.testThing", 400, 1, 0, 1, 0, true, true, false);
        require(Thresholds.apply(List.of(test), List.of()).isEmpty(),
            "a long, undocumented test raises nothing");

        DefInfo longDef = def("Foo.big", 400, 1, 0, 1, 0, true, false, true);
        require(has(Thresholds.apply(List.of(longDef), List.of()), "definition-too-long"),
            "a long definition is reported");

        // The point of measuring locals: the outer signature says two, the loop inside says nine.
        DefInfo wide = def("Foo.threads", 5, 2, 9, 1, 0, false, false, true);
        require(has(Thresholds.apply(List.of(wide), List.of()), "too-many-parameters"),
            "a wide *local* parameter list is reported though the signature is narrow");

        DefInfo documented = def("Foo.ok", 5, 1, 0, 1, 0, true, false, true);
        require(Thresholds.apply(List.of(documented), List.of()).isEmpty(),
            "a short, documented, shallow definition raises nothing");

        DefInfo undocumented = def("Foo.bare", 5, 1, 0, 1, 0, true, false, false);
        require(has(Thresholds.apply(List.of(undocumented), List.of()), "undocumented-public"),
            "an undocumented public definition is reported");

        DefInfo internal = def("Foo.hidden", 5, 1, 0, 1, 0, false, false, false);
        require(Thresholds.apply(List.of(internal), List.of()).isEmpty(),
            "a non-public definition needs no doc comment");

        // Three lines and two branches is dense by ratio and trivial in fact; the floor is what
        // stops every one-line helper being a finding.
        DefInfo tiny = def("Foo.tiny", 2, 1, 0, 1, 9, false, false, true);
        require(Thresholds.apply(List.of(tiny), List.of()).isEmpty(),
            "a very short definition is not called dense");

        // Reported against the local, not the definition it sits in -- the whole point of
        // measuring which local owns the line.
        DefInfo crammed = new DefInfo("Foo.outer", "Foo", "src/Foo.flix", 1, 90, 1, 0, 1, 1, 0,
            44, 57, "Foo.outer.loop", 0, 0, 1, false, false, true, List.of());
        List<SourceMetrics.Smell> found = Thresholds.apply(List.of(crammed), List.of());
        require(found.stream().anyMatch(s -> s.rule().equals("crammed-line")
            && s.line() == 57 && s.detail().contains("Foo.outer.loop")),
            "a crammed line names the local that owns it, and its own line");

        require(has(Thresholds.apply(List.of(),
            List.of(new ModuleInfo("Wide", 3, 40, 0, 99))), "wide-coupling"),
            "a module depending on many others is reported");
        require(Thresholds.apply(List.of(), List.of(new ModuleInfo("Narrow", 3, 40, 40, 1))).isEmpty(),
            "being depended upon is not a finding");
        System.out.println("ThresholdsTest: ok");
    }

    private static DefInfo def(String name, int lines, int params, int localParams, int nesting,
                               int cognitive, boolean isPublic, boolean isTest, boolean hasDoc) {
        return new DefInfo(name, "Foo", "src/Foo.flix", 1, lines, params, localParams, 0, nesting,
            cognitive, 0, 1, name, 0, 0, 1, isPublic, isTest, hasDoc, List.of());
    }

    private static boolean has(List<SourceMetrics.Smell> smells, String rule) {
        return smells.stream().anyMatch(s -> s.rule().equals(rule));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
