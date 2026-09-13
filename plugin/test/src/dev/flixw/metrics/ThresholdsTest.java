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
        // 400 lines against a limit of 60 is 6.7x. The multiple is what lets findings of
        // different kinds be ordered against each other at all.
        require(Thresholds.apply(List.of(longDef), List.of()).stream()
            .anyMatch(s -> Math.abs(s.overBy() - 400.0 / Thresholds.MAX_LINES) < 0.001),
            "a finding knows how far over it is");

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
        DefInfo denseBoundary = def("Foo.denseEnough", 4, 1, 0, 1, 9,
            false, false, true);
        require(has(Thresholds.apply(List.of(denseBoundary), List.of()), "dense"),
            "a four-code-line definition is eligible for a dense finding");

        // Reported against the local, not the definition it sits in -- the whole point of
        // measuring which local owns the line.
        DefInfo crammed = DefInfo.builder("Foo.outer", "Foo", "src/Foo.flix", 1)
            .lines(90).codeLines(80).parameters(1).localDefs(1).nesting(1)
            .maxLineTokens(44).maxLineTokensLine(57).maxLineTokensOwner("Foo.outer.loop")
            .hasDoc(true).build();
        List<SourceMetrics.Smell> found = Thresholds.apply(List.of(crammed), List.of());
        // The owner is the *subject* now, not buried in prose: a consumer can group by it.
        require(found.stream().anyMatch(s -> s.rule().equals("crammed-line")
            && s.line() == 57 && s.subject().equals("Foo.outer.loop")
            && s.actual() == 44 && s.limit() == Thresholds.MAX_LINE_TOKENS),
            "a crammed line names the local that owns it, its line, and both numbers");

        DefInfo calibratedBoundary = DefInfo.builder(
                "Foo.combinator", "Foo", "src/Foo.flix", 1)
            .lines(1).codeLines(1).parameters(1).maxLineTokens(35).hasDoc(true).build();
        require(!has(Thresholds.apply(List.of(calibratedBoundary), List.of()), "crammed-line"),
            "the calibrated 35-token boundary is not reported");

        DefInfo noisyDocs = documentedApi(141, List.of("xs", "f"), """
            - `xs`: the given argument xs.
            - `f`: the function f.
            """);
        List<SourceMetrics.Smell> docFindings = Thresholds.apply(List.of(noisyDocs), List.of());
        require(has(docFindings, "noisy-flixdoc-parameters"),
            "an over-limit public FlixDoc parameter span is reported");
        require(has(docFindings, "redundant-parameter-doc"),
            "a strict two-entry parameter mirror is reported");
        DefInfo docBoundary = documentedApi(140, List.of("xs", "f"), """
            - `xs`: values traversed from left to right.
            - `f`: transforms each value.
            """);
        List<SourceMetrics.Smell> boundaryFindings =
            Thresholds.apply(List.of(docBoundary), List.of());
        require(!has(boundaryFindings, "noisy-flixdoc-parameters")
                && !has(boundaryFindings, "redundant-parameter-doc"),
            "the width boundary and useful parameter prose are not findings");

        require(has(Thresholds.apply(List.of(),
            List.of(new ModuleInfo("Wide", 3, 40, 0, 99))), "wide-coupling"),
            "a module depending on many others is reported");
        require(Thresholds.apply(List.of(), List.of(new ModuleInfo("Narrow", 3, 40, 40, 1))).isEmpty(),
            "being depended upon is not a finding");
        System.out.println("ThresholdsTest: ok");
    }

    private static DefInfo def(String name, int lines, int params, int localParams, int nesting,
                               int cognitive, boolean isPublic, boolean isTest, boolean hasDoc) {
        return DefInfo.builder(name, "Foo", "src/Foo.flix", 1).lines(lines).codeLines(lines)
            .parameters(params).maxLocalParameters(localParams).nesting(nesting)
            .cognitive(cognitive).isPublic(isPublic).isTest(isTest).hasDoc(hasDoc).build();
    }

    private static DefInfo documentedApi(int parameterCharacters, List<String> parameterNames,
                                         String docText) {
        return DefInfo.builder("Foo.api", "Foo", "src/Foo.flix", 1).lines(4).codeLines(4)
            .parameters(parameterNames.size()).isPublic(true).hasDoc(true)
            .flixdocParameterCharacters(parameterCharacters).formalParameterNames(parameterNames)
            .docText(docText).build();
    }

    private static boolean has(List<SourceMetrics.Smell> smells, String rule) {
        return smells.stream().anyMatch(s -> s.rule().equals(rule));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
