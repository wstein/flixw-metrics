package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.LineInfo;
import dev.flixw.metrics.sdk.CompilerModel.Model;
import dev.flixw.metrics.sdk.CompilerModel.SourceInfo;

import java.nio.file.Path;
import java.util.List;

/** Checks aggregation populations and portable source-path policy. */
public final class MetricsTest {
    private MetricsTest() { }

    public static void main(String[] args) {
        DefInfo padded = DefInfo.builder("Api.padded", "Api", "src/Api.flix", 1)
            .lines(100).codeLines(4).cognitive(5).hasDoc(true).build();
        require(Math.abs(padded.cognitiveDensity() - 1.25) < 0.001,
            "blank and comment-only span does not lower cognitive density");

        DefInfo testHelper = def("Test.helper", "test\\Test.flix", true, false, false);
        DefInfo api = def("Api.good", "src/Api.flix", true, true, true);
        Metrics.Report report = Metrics.of(2, model(List.of(testHelper, api)), emptyText());
        require(report.docCoveragePercent() == 100,
            "a public helper under test is outside the documentation population");
        require(report.purityPercent() == 100,
            "a public helper under test is outside the purity population");
        require(Thresholds.apply(List.of(testHelper), List.of()).isEmpty(),
            "Windows-style test paths receive test policy");

        Metrics.Report noApi = Metrics.of(1,
            model(List.of(def("Private.f", "src/Private.flix", false, false, true))), emptyText());
        require(noApi.docCoveragePercent() == -1 && noApi.purityPercent() == -1,
            "an empty population is undefined, not zero percent");
        String json = noApi.render(Metrics.Format.JSON);
        require(json.contains("\"docCoveragePercent\": null")
                && json.contains("\"purityPercent\": null"),
            "undefined percentages are null in JSON");
        String text = noApi.render(Metrics.Format.TEXT);
        require(text.contains("docCoveragePercent: N/A") && text.contains("purityPercent: N/A"),
            "undefined percentages are N/A for people");

        DefInfo ground = DefInfo.builder("Api.ground", "Api", "src/Api.flix", 2)
            .effects(List.of("IO")).build();
        DefInfo polymorphic = DefInfo.builder("Api.poly", "Api", "src/Api.flix", 3)
            .declaredPure(false).effectPolymorphic(true).build();
        Metrics.Report partitioned = Metrics.of(1,
            model(List.of(api, ground, polymorphic)), emptyText());
        require(partitioned.pureDefinitions() == 1
                && partitioned.groundEffectfulDefinitions() == 1
                && partitioned.effectPolymorphicDefinitions() == 1
                && partitioned.effectfulDefinitions() == 2,
            "pure, ground, and polymorphic effects form an explicit definition partition");
        require(!polymorphic.isPure() && polymorphic.effects().isEmpty(),
            "a bare effect variable is non-pure without inventing a concrete capability name");
        require(partitioned.render(Metrics.Format.JSON)
                .contains("\"effectPolymorphic\": true"),
            "native JSON identifies effect-polymorphic definitions");

        Metrics.Report ordered = Metrics.of(2, model(List.of(
            def("Z.last", "src/Z.flix", false, true, true),
            def("A.first", "src/A.flix", false, true, true))), emptyText());
        String orderedJson = ordered.render(Metrics.Format.JSON);
        require(orderedJson.indexOf("\"name\": \"A.first\"")
                < orderedJson.indexOf("\"name\": \"Z.last\""),
            "machine output orders definitions independently of compiler map iteration");

        Model sourceModel = new Model(List.of(), List.of(), new LineInfo(15, 10, 2, 1, 2),
            0, 0, 0, 0, 0, 0, List.of(
                new SourceInfo("src/Z.flix", new LineInfo(5, 3, 1, 0, 1)),
                new SourceInfo("src/A.flix", new LineInfo(10, 7, 1, 1, 1))), List.of());
        Metrics.Report bySource = Metrics.of(2, sourceModel, emptyText());
        String sourceJson = bySource.render(Metrics.Format.JSON);
        require(sourceJson.contains("\"sources\": [")
                && sourceJson.contains("\"file\": \"src/A.flix\", \"lines\": 10,"
                    + " \"codeLines\": 7, \"commentLines\": 1,"
                    + " \"docCommentLines\": 1, \"blankLines\": 1")
                && sourceJson.indexOf("\"file\": \"src/A.flix\"")
                    < sourceJson.indexOf("\"file\": \"src/Z.flix\""),
            "native JSON exposes deterministic per-source line measurements");
        require(bySource.ranks().stream().anyMatch(rank ->
                rank.measure().equals("largest-file")
                    && rank.subject().equals("src/A.flix") && rank.actual() == 10),
            "the largest-file ranking locates the biggest physical source");
        System.out.println("MetricsTest: ok");
    }

    private static DefInfo def(String name, String file, boolean isPublic, boolean pure,
                               boolean documented) {
        return DefInfo.builder(name, "Api", file, 1).lines(1).codeLines(1)
            .isPublic(isPublic).hasDoc(documented).effects(pure ? List.of() : List.of("IO"))
            .build();
    }

    private static Model model(List<DefInfo> defs) {
        return new Model(defs, List.of(), new LineInfo(1, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0);
    }

    private static SourceMetrics emptyText() {
        return new SourceMetrics(1, 1, 0, List.of());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
