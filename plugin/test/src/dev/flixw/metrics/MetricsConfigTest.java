package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** Checks strict project configuration, overrides, and scoped suppressions. */
public final class MetricsConfigTest {
    private MetricsConfigTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("flixw-metrics-config-");
        try {
            Files.writeString(root.resolve(".flixw-metrics.properties"), """
                rules.definition-too-long.limit=100
                rules.dense.enabled=false
                rules.line-too-long.limit=120
                suppressions.legacy.rule=definition-too-long
                suppressions.legacy.subject=Legacy.*
                suppressions.legacy.reason=scheduled refactor
                suppressions.legacy.until=2026-09-12
                """);
            MetricsConfig config = MetricsConfig.read(root, LocalDate.parse("2026-09-12"));
            require(config.limit(RuleDefinitions.DEFINITION_TOO_LONG) == 100,
                "a numeric threshold can be overridden");
            require(!config.enabled(RuleDefinitions.DENSE), "a rule can be disabled");

            Path source = root.resolve("src/Long.flix");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "x".repeat(110) + "\n");
            require(SourceMetrics.measure(root, List.of(source), config).smells().isEmpty(),
                "text-derived rules use the same configured limits");

            DefInfo ordinary = def("Api.big", 80, 100);
            require(Thresholds.apply(List.of(ordinary), List.of(), config).isEmpty(),
                "configured limits and disabled rules drive findings");
            DefInfo legacy = def("Legacy.big", 101, 0);
            List<SourceMetrics.Smell> found = Thresholds.apply(List.of(legacy), List.of(), config);
            require(found.size() == 1 && config.isSuppressed(found.get(0)),
                "a justified scoped suppression matches its finding through its expiry date");
            var model = new dev.flixw.metrics.sdk.CompilerModel.Model(List.of(legacy), List.of(),
                new dev.flixw.metrics.sdk.CompilerModel.LineInfo(1, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0);
            Metrics.Report filtered = Metrics.of(1, model,
                new SourceMetrics(1, 1, 0, List.of()), config);
            require(filtered.smells().isEmpty(),
                "report derivation removes configured suppressions but keeps measurements");
            String markdown = filtered.render(Metrics.Format.MARKDOWN, null, config);
            require(markdown.contains("definition-too-long=100") && markdown.contains("dense=off"),
                "human reports disclose their effective policy");
            String json = filtered.render(Metrics.Format.JSON, null, config);
            require(json.contains("\"configuration\"")
                    && json.contains("\"definition-too-long\": {\"enabled\": true, \"limit\": 100}"),
                "machine reports disclose their effective policy");

            MetricsConfig expired = MetricsConfig.read(root, LocalDate.parse("2026-09-13"));
            require(!expired.isSuppressed(found.get(0)), "an expired suppression stops matching");

            Files.writeString(root.resolve(".flixw-metrics.properties"),
                "rules.not-a-rule.enabled=false\n");
            requireInvalid(root, "unknown rule ids fail explicitly");
            Files.writeString(root.resolve(".flixw-metrics.properties"), """
                suppressions.bad.rule=*
                suppressions.bad.file=generated/**
                """);
            requireInvalid(root, "a suppression requires a reason");
            System.out.println("MetricsConfigTest: ok");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static DefInfo def(String name, int lines, int cognitive) {
        return new DefInfo(name, "Api", "src/Api.flix", 1, lines, lines, 0, 0, 0, 0, cognitive,
            0, 1, name, 0, 0, 1, false, false, true, List.of());
    }

    private static void requireInvalid(Path root, String message) {
        try {
            MetricsConfig.read(root);
            throw new AssertionError(message);
        } catch (MetricsConfig.Invalid expected) {
            require(expected.getMessage().contains(".flixw-metrics.properties"),
                "configuration errors name their source file");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
