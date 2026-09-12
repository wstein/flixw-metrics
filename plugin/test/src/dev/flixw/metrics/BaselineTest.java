package dev.flixw.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exercises report-baseline compatibility and observation lifecycle semantics. */
public final class BaselineTest {
    private BaselineTest() { }

    public static void main(String[] args) throws Exception {
        SourceMetrics.Smell kept = smell("dense", "A.kept", 3, 1.1, 1);
        SourceMetrics.Smell worsenedBefore = smell("deeply-nested", "A.changed", 7, 5, 4);
        SourceMetrics.Smell movedBefore = smell("line-too-long", "src/A.flix:11", 11, 120, 100);
        SourceMetrics.Smell resolved = smell("too-many-parameters", "A.gone", 20, 7, 5);
        SourceMetrics.Smell improvedBefore = smell("definition-too-long", "A.better", 24, 90, 60);
        Metrics.Report before = FormatsTest.reportWithSmells(
            List.of(kept, worsenedBefore, movedBefore, resolved, improvedBefore));

        SourceMetrics.Smell worsenedAfter = smell("deeply-nested", "A.changed", 7, 8, 4);
        SourceMetrics.Smell movedAfter = smell("line-too-long", "src/A.flix:12", 12, 120, 100);
        SourceMetrics.Smell added = smell("definition-too-long", "A.new", 30, 80, 60);
        SourceMetrics.Smell improvedAfter = smell("definition-too-long", "A.better", 24, 70, 60);
        Metrics.Report after = FormatsTest.reportWithSmells(
            List.of(kept, worsenedAfter, movedAfter, added, improvedAfter));

        Path baseline = Files.createTempFile("flixw-metrics-baseline-", ".json");
        try {
            Files.writeString(baseline, before.render(Metrics.Format.JSON));
            Baseline.Comparison comparison = Baseline.compare(
                baseline, after, MetricsConfig.defaults());
            require(comparison.added().size() == 2,
                "an added and a moved observation are new");
            require(comparison.worsened().size() == 1
                    && comparison.worsened().get(0).after().actual() == 8,
                "a stable observation with a larger threshold multiple is worsened");
            require(comparison.resolved().size() == 2,
                "a removed and the old side of a move are resolved");
            require(comparison.retained() == 2,
                "identical and improved observations are retained without failing the gate");
            require(comparison.crosses("warning"),
                "new or worsened warnings cross a warning-only gate");
            require(!comparison.crosses("error"),
                "new or worsened warnings do not cross an error-only gate");

            String changedSchema = before.render(Metrics.Format.JSON)
                .replaceFirst("\"schemaVersion\": [0-9]+", "\"schemaVersion\": 0");
            Files.writeString(baseline, changedSchema);
            expectInvalid(() -> Baseline.compare(baseline, after, MetricsConfig.defaults()),
                "schema version");

            Path configured = Files.createTempDirectory("flixw-metrics-policy-");
            try {
                Files.writeString(configured.resolve(MetricsConfig.FILE),
                    "rules.dense.enabled=false\n");
                Files.writeString(baseline, before.render(Metrics.Format.JSON));
                expectInvalid(() -> Baseline.compare(
                    baseline, after, MetricsConfig.read(configured)), "configuration");
            } finally {
                Files.deleteIfExists(configured.resolve(MetricsConfig.FILE));
                Files.deleteIfExists(configured);
            }
        } finally {
            Files.deleteIfExists(baseline);
        }
        System.out.println("BaselineTest: ok");
    }

    private static SourceMetrics.Smell smell(String rule, String subject, int line,
                                             double actual, double limit) {
        return new SourceMetrics.Smell(rule, subject, "src/A.flix", line, actual, limit, "", "x");
    }

    private static void expectInvalid(Throwing action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected invalid baseline containing " + message);
        } catch (Baseline.Invalid e) {
            require(e.getMessage().contains(message), "diagnostic names " + message);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Throwing { void run() throws Exception; }
}
