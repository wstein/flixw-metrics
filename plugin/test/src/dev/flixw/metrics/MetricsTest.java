package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.LineInfo;
import dev.flixw.metrics.sdk.CompilerModel.Model;

import java.nio.file.Path;
import java.util.List;

/** Checks aggregation populations and portable source-path policy. */
public final class MetricsTest {
    private MetricsTest() { }

    public static void main(String[] args) {
        DefInfo padded = new DefInfo("Api.padded", "Api", "src/Api.flix", 1, 100, 4,
            0, 0, 0, 0, 5, 0, 1, "Api.padded", 0, 0, 1, false, false, true, List.of());
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
        System.out.println("MetricsTest: ok");
    }

    private static DefInfo def(String name, String file, boolean isPublic, boolean pure,
                               boolean documented) {
        return new DefInfo(name, "Api", file, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, name,
            0, 0, 1, isPublic, false, documented, pure ? List.of() : List.of("IO"));
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
