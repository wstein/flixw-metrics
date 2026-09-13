package dev.flixw.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import dev.flixw.metrics.sdk.CompilerModel;

/** Exercises report-baseline compatibility and observation lifecycle semantics. */
public final class BaselineTest {
    private BaselineTest() { }

    public static void main(String[] args) throws Exception {
        stableFindingIdentity();
        measurementDeltas();

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

            Files.writeString(baseline, before.render(Metrics.Format.JSON, null,
                MetricsConfig.defaults(), null, Metrics.View.FINDINGS,
                PresentationFilter.of("dense", null, null)));
            expectInvalid(() -> Baseline.compare(baseline, after, MetricsConfig.defaults()),
                "filtered report cannot be used as a baseline");

            String previousSchema = before.render(Metrics.Format.JSON)
                .replaceFirst("\"schemaVersion\": [0-9]+", "\"schemaVersion\": 24")
                .replace("\"id\": \"" + kept.id() + "\"",
                    "\"id\": \"" + kept.legacyId() + "\"");
            Files.writeString(baseline, previousSchema);
            Baseline.Comparison migrated = Baseline.compare(
                baseline, after, MetricsConfig.defaults());
            require(migrated.retained() == comparison.retained(),
                "schema 24 baselines and their location-sensitive IDs migrate in place");

            Files.writeString(baseline, before.render(Metrics.Format.JSON)
                .replaceFirst("\"schemaVersion\": [0-9]+", "\"schemaVersion\": 25"));
            require(Baseline.compare(baseline, after, MetricsConfig.defaults()).retained()
                    == comparison.retained(),
                "schema 25 baselines migrate after structured locations are added");

            Files.writeString(baseline, before.render(Metrics.Format.JSON)
                .replaceFirst("\"schemaVersion\": [0-9]+", "\"schemaVersion\": 26"));
            require(Baseline.compare(baseline, after, MetricsConfig.defaults()).retained()
                    == comparison.retained(),
                "schema 26 baselines migrate after local locations are added");

            String changedSchema = before.render(Metrics.Format.JSON)
                .replaceFirst("\"schemaVersion\": [0-9]+", "\"schemaVersion\": 0");
            Files.writeString(baseline, changedSchema);
            expectInvalid(() -> Baseline.compare(baseline, after, MetricsConfig.defaults()),
                "schema version");

            Files.writeString(baseline, "[".repeat(10_000) + "0" + "]".repeat(10_000));
            expectInvalid(() -> Baseline.compare(baseline, after, MetricsConfig.defaults()),
                "nesting exceeds");

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

    private static void measurementDeltas() throws Exception {
        Metrics.Report before = deltaReport(10, 20, 1);
        Metrics.Report after = deltaReport(7, 15, 3);
        Path baseline = Files.createTempFile("flixw-metrics-measurements-", ".json");
        try {
            Files.writeString(baseline, before.render(Metrics.Format.JSON));
            Baseline.Comparison comparison = Baseline.compare(
                baseline, after, MetricsConfig.defaults());
            require(comparison.measurementDeltas().stream().anyMatch(d ->
                    d.scope().equals("summary") && d.metric().equals("cognitive")
                    && d.before().doubleValue() == 10 && d.after().doubleValue() == 7),
                "summary measurements change even when no threshold finding changes");
            require(comparison.measurementDeltas().stream().anyMatch(d ->
                    d.scope().equals("definition") && d.subject().equals("A.f")
                    && d.metric().equals("lines") && d.delta().doubleValue() == -5),
                "definition measurement changes retain their semantic owner");
            require(comparison.measurementDeltas().stream().anyMatch(d ->
                    d.scope().equals("module") && d.subject().equals("A")
                    && d.metric().equals("fanIn") && d.delta().doubleValue() == 2),
                "module coupling changes are visible below finding thresholds");
            String json = comparison.json();
            require(json.contains("\"measurementDeltas\": [")
                    && json.contains("\"scope\": \"definition\"")
                    && json.contains("\"delta\": -5"),
                "native baseline output exposes typed measurement deltas");
        } finally {
            Files.deleteIfExists(baseline);
        }
    }

    private static Metrics.Report deltaReport(int cognitive, int definitionLines, int fanIn) {
        CompilerModel.DefInfo def = CompilerModel.DefInfo.builder(
                "A.f", "A", "src/A.flix", 10)
            .lines(definitionLines).codeLines(definitionLines).cognitive(cognitive)
            .hasDoc(true).build();
        CompilerModel.ModuleInfo module = new CompilerModel.ModuleInfo(
            "A", 1, definitionLines, fanIn, 1);
        return new Metrics.Report(1, 1, 1, 0, 0, 0, 0, 0, cognitive, 0, 0, 0, 0, 0, 0,
            definitionLines, definitionLines, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 100, 100, List.of(), List.of(), List.of(), List.of(def), List.of(module),
            List.of());
    }

    private static void stableFindingIdentity() throws Exception {
        SourceMetrics.Smell definitionBefore = smell("dense", "A.f", 10, 2, 1);
        SourceMetrics.Smell definitionAfter = smell("dense", "A.f", 30, 2, 1);
        require(definitionBefore.id().equals(definitionAfter.id()),
            "a symbol-owned finding survives unrelated lines inserted above it");

        Path root = Files.createTempDirectory("flixw-metrics-fingerprint-");
        Path source = root.resolve("src/A.flix");
        try {
            Files.createDirectories(source.getParent());
            String longLine = "x".repeat(SourceMetrics.LINE_LIMIT + 1);
            Files.writeString(source, longLine + "\nshort\n" + longLine + "\n");
            List<SourceMetrics.Smell> before = SourceMetrics.measure(root, List.of(source)).smells();
            require(before.size() == 2 && !before.get(0).id().equals(before.get(1).id()),
                "identical offending lines receive collision-safe identities");

            Files.writeString(source, "inserted\n" + longLine + "\nshort\n" + longLine + "\n");
            List<SourceMetrics.Smell> after = SourceMetrics.measure(root, List.of(source)).smells();
            require(before.get(0).id().equals(after.get(0).id())
                    && before.get(1).id().equals(after.get(1).id()),
                "line-only findings use content and occurrence rather than physical line number");
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(source.getParent());
            Files.deleteIfExists(root);
        }
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
