package dev.flixw.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/** Exercises git provenance, ordinary non-checkouts, and the subprocess deadline. */
public final class ProvenanceTest {
    private ProvenanceTest() { }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("flixw-metrics-provenance-");
        try {
            Path project = work.resolve("project");
            Files.createDirectories(project);
            Path source = project.resolve("A.flix");
            Path compiler = work.resolve("compiler.jar");
            Files.writeString(source, "def a(): Int32 = 1\n");
            Files.writeString(compiler, "compiler\n");
            Main.Context context = new Main.Context(project, compiler, Path.of("java"), null);

            String digest = ResultCache.key(context, List.of(source), "test");
            Provenance outside = Provenance.of(context, "test", digest);
            require(outside.commit().equals("(not a git checkout)") && !outside.dirty(),
                "a non-checkout has explicit fallback provenance and is not spuriously dirty");
            require(outside.inputDigest().equals(digest),
                "provenance reuses the cache digest supplied by its caller");

            run(project, "git", "init", "--quiet");
            run(project, "git", "config", "user.email", "metrics@example.invalid");
            run(project, "git", "config", "user.name", "Metrics Test");
            run(project, "git", "add", "A.flix");
            run(project, "git", "commit", "--quiet", "-m", "fixture");
            Provenance clean = Provenance.of(context, "test", digest);
            require(clean.commit().matches("[0-9a-f]{40}") && !clean.dirty(),
                "a clean checkout records its commit and clean state");

            Files.writeString(source, "def a(): Int32 = 2\n");
            require(Provenance.of(context, "test", digest).dirty(),
                "an edited checkout is marked dirty");

            long started = System.nanoTime();
            String timedOut = Provenance.command(project, Duration.ofMillis(50),
                List.of("/bin/sh", "-c", "sleep 2; printf late"));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            require(timedOut.isEmpty() && elapsedMillis < 1_000,
                "the deadline applies before stdout is read");
            System.out.println("ProvenanceTest: ok");
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void run(Path root, String... command) throws Exception {
        int status = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start()
            .waitFor();
        require(status == 0, "fixture command succeeds: " + String.join(" ", command));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
