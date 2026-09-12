package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;

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
