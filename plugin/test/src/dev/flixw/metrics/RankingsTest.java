package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.ModuleInfo;
import dev.flixw.metrics.sdk.CompilerModel.LineInfo;
import dev.flixw.metrics.sdk.CompilerModel.SourceInfo;

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
        String densityJson = enoughDensity.json(MetricsConfig.defaults(), 2);
        require(densityJson.contains("\"actual\": 2")
                && densityJson.contains("\"unit\": \"complexity per code line\"")
                && densityJson.contains("\"ordinal\": 2")
                && densityJson.contains("\"ruleId\": \"dense\"")
                && densityJson.contains("\"limit\": 1")
                && densityJson.contains("\"crossedThreshold\": true"),
            "rank JSON exposes typed values and its related effective policy");
        require(tinyDensity.json(MetricsConfig.defaults(), 1)
                .contains("\"crossedThreshold\": false"),
            "structurally ineligible ranks do not claim to cross a finding threshold");

        Rankings.Rank longestTest = rank(ranks, "longest", "A.longTest");
        require(!longestTest.eligible() && longestTest.ineligibilityReason().contains("test"),
            "a ranked test discloses its definition-too-long exemption");

        DefInfo publicApi = DefInfo.builder("A.publicApi", "A", "src/A.flix", 1)
            .lines(1).codeLines(1).parameters(2).isPublic(true).hasDoc(true)
            .flixdocParameterCharacters(120).formalParameterNames(List.of("input", "predicate"))
            .docText("Returns matching values.").flixdocResultCharacters(80).build();
        List<Rankings.Rank> apiRanks = Rankings.of(List.of(publicApi), List.of());
        require(rank(apiRanks, "longest-flixdoc-parameters", "A.publicApi").value()
                .equals("120 rendered characters"),
            "public FlixDoc parameter spans have their own ranking");
        require(rank(apiRanks, "longest-flixdoc-result", "A.publicApi").value()
                .equals("80 rendered characters"),
            "public FlixDoc result types have their own ranking");

        DefInfo effectfulDatalog = DefInfo.builder("A.orchestrate", "A", "src/A.flix", 2)
            .lines(5).codeLines(5).datalogRules(3).datalogFacts(1).isPublic(true).hasDoc(true)
            .effects(List.of("Console", "FileRead", "Network"))
            .datalogDependencies(List.of("Edge", "Path", "Reachable"))
            .datalogDependencyDepth(4).recursiveDatalogPredicates(List.of("Path", "Reachable"))
            .flixdocResultCharacters(7).build();
        List<Rankings.Rank> semanticRanks = Rankings.of(List.of(effectfulDatalog), List.of());
        require(rank(semanticRanks, "widest-effect-surface", "A.orchestrate").value()
                .equals("3 declared effects"),
            "declared effect-set width has its own ranking");
        require(rank(semanticRanks, "widest-effect-surface", "A.orchestrate")
                .json(MetricsConfig.defaults(), 1).contains("\"ruleId\": null"),
            "a measurement without a finding rule says so explicitly");
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

        List<Rankings.Rank> sourceRanks = Rankings.of(List.of(), List.of(), List.of(
            new SourceInfo("src/Small.flix", new LineInfo(10, 8, 0, 0, 2)),
            new SourceInfo("src/Large.flix", new LineInfo(40, 20, 8, 4, 8))));
        Rankings.Rank largest = rank(sourceRanks, "largest-file", "src/Large.flix");
        require(largest.actual() == 40 && largest.unit().equals("lines")
                && largest.file().equals("src/Large.flix") && largest.line() == 1
                && largest.ruleId().isEmpty(),
            "source size is ranked by physical lines without inventing a finding rule");

        System.out.println("RankingsTest: ok");
    }

    private static DefInfo definition(String name, String file, int codeLines, int cognitive,
                                      boolean isTest) {
        return DefInfo.builder(name, "A", file, 1).lines(codeLines).codeLines(codeLines)
            .nesting(1).cognitive(cognitive).maxLineTokens(1).isTest(isTest).hasDoc(true).build();
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
