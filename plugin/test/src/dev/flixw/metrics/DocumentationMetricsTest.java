package dev.flixw.metrics;

import java.util.List;

/** Conservative recognition of parameter documentation: ambiguity means no finding. */
public final class DocumentationMetricsTest {
    private DocumentationMetricsTest() { }

    public static void main(String[] args) {
        var mirrored = DocumentationMetrics.parameters(List.of("xs", "f"), """
            Parameters:
            - `xs`: the given argument xs.
            - `f`: the function f.
            """);
        require(mirrored.entries() == 2 && mirrored.redundantEntries() == 2,
            "a mechanical mirror of two real formal parameters is recognized");

        var useful = DocumentationMetrics.parameters(List.of("xs", "f"), """
            - `xs`: values traversed from left to right.
            - `f`: transforms each value before accumulation.
            """);
        require(useful.entries() == 2 && useful.redundantEntries() == 0,
            "domain information is not mistaken for a signature mirror");

        var unrelated = DocumentationMetrics.parameters(List.of("xs", "f"), """
            - `linear`: fixed delay between retries.
            - `exponential`: doubling delay between retries.
            """);
        require(unrelated.entries() == 0 && unrelated.redundantEntries() == 0,
            "an ordinary named list is not parameter documentation");

        var prose = DocumentationMetrics.parameters(List.of("x", "f"),
            "Given `x` and `f`, returns `f(x)` without changing effects.");
        require(prose.entries() == 0 && prose.redundantEntries() == 0,
            "free prose remains outside the deliberately narrow heuristic");

        System.out.println("DocumentationMetricsTest: ok");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
