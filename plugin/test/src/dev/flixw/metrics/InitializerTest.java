package dev.flixw.metrics;

import java.nio.file.Files;
import java.nio.file.Path;

/** Checks safe, reviewable project onboarding without launching the compiler. */
public final class InitializerTest {
    private InitializerTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("flixw-metrics-init-");
        try {
            String message = Initializer.write(root, "{\"schemaVersion\": 17}\n");
            Path policy = root.resolve(MetricsConfig.FILE);
            Path baseline = root.resolve(Initializer.BASELINE_FILE);
            require(Files.readString(policy).contains("rules.definition-too-long.limit")
                    && Files.readString(policy).contains("exclusions.generated.file"),
                "the generated policy documents rule overrides and source exclusions");
            MetricsConfig.read(root);
            require(Files.readString(baseline).equals("{\"schemaVersion\": 17}\n"),
                "init preserves the native JSON report as its baseline");
            require(message.contains("created .flixw-metrics.properties")
                    && message.contains("created metrics-baseline.json")
                    && message.contains("./flixw metrics report --baseline metrics-baseline.json"
                        + " --fail-on-new warning"),
                "init prints its outputs and a ready-to-use CI gate");

            String originalPolicy = Files.readString(policy);
            String originalBaseline = Files.readString(baseline);
            expectExisting(root, ".flixw-metrics.properties");
            require(Files.readString(policy).equals(originalPolicy)
                    && Files.readString(baseline).equals(originalBaseline),
                "a repeated init does not overwrite either reviewed file");

            Path baselineOnly = Files.createTempDirectory("flixw-metrics-init-baseline-");
            try {
                Files.writeString(baselineOnly.resolve(Initializer.BASELINE_FILE), "keep\n");
                expectExisting(baselineOnly, "metrics-baseline.json");
                require(!Files.exists(baselineOnly.resolve(MetricsConfig.FILE)),
                    "preflight does not create policy when a baseline already exists");
            } finally {
                delete(baselineOnly);
            }
            System.out.println("InitializerTest: ok");
        } finally {
            delete(root);
        }
    }

    private static void expectExisting(Path root, String name) {
        try {
            Initializer.preflight(root);
            throw new AssertionError("expected existing-file diagnostic");
        } catch (Main.Usage expected) {
            require(expected.getMessage().contains(name),
                "the diagnostic names the file that would be overwritten");
        }
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
