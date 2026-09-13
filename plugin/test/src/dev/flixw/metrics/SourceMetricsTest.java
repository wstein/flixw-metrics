package dev.flixw.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Directly checks source-file aggregation and the exact line-length boundary. */
public final class SourceMetricsTest {
    private SourceMetricsTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("flixw-metrics-source-");
        try {
            Path first = root.resolve("src/A.flix");
            Path second = root.resolve("src/nested/B.flix");
            Files.createDirectories(second.getParent());
            Files.writeString(first, "x".repeat(SourceMetrics.LINE_LIMIT) + "\nshort\n");
            Files.writeString(second, "y".repeat(SourceMetrics.LINE_LIMIT + 1) + "\n");

            SourceMetrics measured = SourceMetrics.measure(root, List.of(first, second));
            require(measured.lines() == 3,
                "line totals aggregate across files without counting trailing newlines");
            require(measured.longestLine() == SourceMetrics.LINE_LIMIT + 1,
                "the longest line is selected across the whole source set");
            require(measured.linesOverLimit() == 1 && measured.smells().size() == 1,
                "the exact boundary is accepted and only boundary-plus-one is reported");
            SourceMetrics.Smell smell = measured.smells().get(0);
            require(smell.file().equals("src/nested/B.flix") && smell.line() == 1
                    && smell.actual() == SourceMetrics.LINE_LIMIT + 1,
                "the multi-file finding keeps its portable relative path and local line");
            System.out.println("SourceMetricsTest: ok");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
