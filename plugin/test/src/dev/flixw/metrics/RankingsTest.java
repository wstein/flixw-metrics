package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.ModuleInfo;

import java.util.List;

/** Structural finding eligibility travels with the ranked entry it qualifies. */
public final class RankingsTest {
    private RankingsTest() { }

    public static void main(String[] args) {
        DefInfo tiny = definition("A.tiny", "src/A.flix", 3, 9, false);
        DefInfo enough = definition("A.enough", "src/A.flix", 4, 8, false);
        DefInfo test = definition("A.longTest", "test/A.flix", 20, 1, true);

        List<Rankings.Rank> ranks = Rankings.of(List.of(tiny, enough, test), List.of());
        Rankings.Rank tinyDensity = rank(ranks, "densest", "A.tiny");
        require(!tinyDensity.eligible(), "a three-code-line density rank is ineligible");
        require(tinyDensity.ineligibilityReason().equals(
                "requires at least 4 code lines (has 3)"),
            "the dense reason states both the floor and observed size");

        Rankings.Rank enoughDensity = rank(ranks, "densest", "A.enough");
        require(enoughDensity.eligible() && enoughDensity.ineligibilityReason().isEmpty(),
            "a four-code-line density rank is eligible without a stale reason");

        Rankings.Rank longestTest = rank(ranks, "longest", "A.longTest");
        require(!longestTest.eligible() && longestTest.ineligibilityReason().contains("test"),
            "a ranked test discloses its definition-too-long exemption");

        DefInfo publicApi = new DefInfo("A.publicApi", "A", "src/A.flix", 1, 1, 1,
            2, 0, 0, 0, 0, 0, 1, "A.publicApi", 0, 0, 1, true, false, true, List.of(),
            120, List.of("input", "predicate"), "Returns matching values.", List.of(), 0,
            List.of(), 80);
        List<Rankings.Rank> apiRanks = Rankings.of(List.of(publicApi), List.of());
        require(rank(apiRanks, "longest-flixdoc-parameters", "A.publicApi").value()
                .equals("120 rendered characters"),
            "public FlixDoc parameter spans have their own ranking");
        require(rank(apiRanks, "longest-flixdoc-result", "A.publicApi").value()
                .equals("80 rendered characters"),
            "public FlixDoc result types have their own ranking");

        DefInfo effectfulDatalog = new DefInfo("A.orchestrate", "A", "src/A.flix", 2, 5, 5,
            0, 0, 0, 0, 0, 0, 2, "A.orchestrate", 3, 1, 1, true, false, true,
            List.of("Console", "FileRead", "Network"), 0, List.of(), "",
            List.of("Edge", "Path", "Reachable"), 4, List.of("Path", "Reachable"), 7);
        List<Rankings.Rank> semanticRanks = Rankings.of(List.of(effectfulDatalog), List.of());
        require(rank(semanticRanks, "widest-effect-surface", "A.orchestrate").value()
                .equals("3 declared effects"),
            "declared effect-set width has its own ranking");
        require(rank(semanticRanks, "widest-datalog-dependency", "A.orchestrate").value()
                .equals("3 body predicates"),
            "distinct Datalog body-predicate breadth has its own ranking");
        require(rank(semanticRanks, "deepest-datalog-dependency", "A.orchestrate").value()
                .equals("4 predicate levels"),
            "the collapsed Datalog dependency graph has its own depth ranking");
        require(rank(semanticRanks, "most-recursive-datalog", "A.orchestrate").value()
                .equals("2 recursive predicates"),
            "recursive predicate participation has its own ranking");

        List<Rankings.Rank> impactRanks = Rankings.of(List.of(), List.of(
            new ModuleInfo("Foundation", 10, 100, 7, 1),
            new ModuleInfo("Leaf", 2, 20, 1, 4)));
        require(rank(impactRanks, "highest-change-impact", "Foundation").value()
                .equals("7 dependent modules, definition-call fan-in"),
            "module fan-in has an explicitly scoped change-impact ranking");

        System.out.println("RankingsTest: ok");
    }

    private static DefInfo definition(String name, String file, int codeLines, int cognitive,
                                      boolean isTest) {
        return new DefInfo(name, "A", file, 1, codeLines, codeLines, 0, 0, 0, 1, cognitive,
            1, 1, name, 0, 0, 1, false, isTest, true, List.of());
    }

    private static Rankings.Rank rank(List<Rankings.Rank> ranks, String measure, String subject) {
        return ranks.stream()
            .filter(rank -> rank.measure().equals(measure) && rank.subject().equals(subject))
            .findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
