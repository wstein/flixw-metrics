package dev.flixw.metrics;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.spi.ToolProvider;

/** Checks that capability detection is based on loadable compiler classes, not filenames. */
public final class CompilerCapabilitiesTest {
    private CompilerCapabilitiesTest() { }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("flixw-metrics-test-");
        try {
            Path empty = work.resolve("empty.jar");
            jar(empty, work.resolve("none"));
            CompilerCapabilities noApi = inspect(empty);
            require(!noApi.hasFlixApi() && !noApi.hasReflectionApi() && !noApi.hasNativeMetrics(),
                "empty jar has no capabilities");
            require(!noApi.missing().isEmpty(), "an empty jar reports what it is missing");

            Path source = work.resolve("source");
            write(source, "ca/uwaterloo/flix/api/Flix.java",
                "package ca.uwaterloo.flix.api; public final class Flix { }");
            write(source, "ca/uwaterloo/flix/tools/Metrics$.java",
                "package ca.uwaterloo.flix.tools; public final class Metrics$ { }");
            Path classes = work.resolve("classes");
            Files.createDirectories(classes);
            int compiled = ToolProvider.findFirst("javac").orElseThrow()
                .run(System.out, System.err, "-d", classes.toString(),
                    source.resolve("ca/uwaterloo/flix/api/Flix.java").toString(),
                    source.resolve("ca/uwaterloo/flix/tools/Metrics$.java").toString());
            require(compiled == 0, "fixture compiler succeeds");
            Path metrics = work.resolve("metrics.jar");
            jar(metrics, classes);
            CompilerCapabilities found = inspect(metrics);
            require(found.hasFlixApi() && !found.hasReflectionApi() && found.hasNativeMetrics(),
                "fixture distinguishes model presence from the reflection API");

            // The gate must name the members the engine truly calls. A jar carrying a Flix
            // class with the *old* gate's members and none of the engine's must still fail:
            // that combination is exactly what used to pass and then die mid-run.
            require(found.missing().stream().anyMatch(m -> m.contains("Bootstrap")),
                "the gate requires Bootstrap, which the engine calls");
            require(found.missing().stream().anyMatch(m -> m.contains("Options$")),
                "the gate requires Options$, which the engine calls");
            require(found.missing().stream().noneMatch(m -> m.contains("addFile")),
                "the gate does not require addFile, which the engine never calls");
        } finally {
            delete(work);
        }
    }

    /** Inspection needs a loader now; the gate never initialises through it. */
    private static CompilerCapabilities inspect(Path jar) throws IOException {
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            return CompilerCapabilities.inspect(loader, jar);
        }
    }

    private static void write(Path base, String relative, String source) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static void jar(Path output, Path contents) throws IOException, InterruptedException {
        Files.createDirectories(contents);
        int status = new ProcessBuilder("jar", "--create", "--file", output.toString(), "-C",
            contents.toString(), ".").inheritIO().start().waitFor();
        require(status == 0, "fixture jar succeeds");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
