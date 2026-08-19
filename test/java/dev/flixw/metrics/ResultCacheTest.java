package dev.flixw.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks the one property a cache has to have: it may make the plugin faster, and it may never
 * make it disagree with the compiler.
 *
 * <p>Every case here is an input that changes the report, asserted to change the key. The
 * expensive half -- that a hit returns the same numbers a cold run produced -- is an
 * integration concern and belongs with the compiler fixture; what is checkable without a
 * compiler is that the key notices.
 */
public final class ResultCacheTest {
    private ResultCacheTest() { }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("flixw-metrics-cache-");
        try {
            Path project = work.resolve("project");
            Path src = project.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("A.flix"), "def a(): Int32 = 1\n");
            Path jar = work.resolve("compiler.jar");
            Files.writeString(jar, "pretend compiler\n");

            Main.Context context = new Main.Context(project, jar, Path.of("java"), work);
            List<Path> sources = List.of(src.resolve("A.flix"));
            String base = ResultCache.key(context, sources, "1.0.0");
            require(base != null, "a key can be computed");

            // Same inputs, same key -- otherwise nothing is ever a hit.
            require(base.equals(ResultCache.key(context, sources, "1.0.0")), "the key is stable");

            // A source edit changes the report, so it must change the key.
            Files.writeString(src.resolve("A.flix"), "def a(): Int32 = 2\n");
            require(!base.equals(ResultCache.key(context, sources, "1.0.0")),
                "editing a source changes the key");
            Files.writeString(src.resolve("A.flix"), "def a(): Int32 = 1\n");

            // A new file changes `files` and probably `definitions`.
            Files.writeString(src.resolve("B.flix"), "def b(): Int32 = 2\n");
            List<Path> two = List.of(src.resolve("A.flix"), src.resolve("B.flix"));
            require(!base.equals(ResultCache.key(context, two, "1.0.0")),
                "adding a file changes the key");

            // The compiler carries the standard library, so its bytes are part of the answer.
            Files.writeString(jar, "a different compiler\n");
            require(!base.equals(ResultCache.key(context, sources, "1.0.0")),
                "a different compiler changes the key");
            Files.writeString(jar, "pretend compiler\n");

            // A new plugin may count the same root differently.
            require(!base.equals(ResultCache.key(context, sources, "1.0.1")),
                "a plugin version change changes the key");

            // The manifest selects dependencies that participate in typing.
            Files.writeString(project.resolve("flix.toml"), "[package]\n");
            require(!base.equals(ResultCache.key(context, sources, "1.0.0")),
                "adding flix.toml changes the key");
            Files.delete(project.resolve("flix.toml"));

            // Round trip, including the shapes that must not be trusted.
            ResultCache.write(work, base, new ReflectionEngine.Report(1, 2, 3, 4, 5, 6, 7, 8)
                .render(ReflectionEngine.Format.JSON));
            ReflectionEngine.Report back = ReflectionEngine.Report.fromJson(
                ResultCache.read(work, base));
            require(back != null && back.files() == 1 && back.definitions() == 2
                && back.typeAliases() == 8, "a written entry reads back intact");
            require(ResultCache.read(work, "0".repeat(64)) == null, "an absent entry is a miss");
            require(ReflectionEngine.Report.fromJson("{\"schemaVersion\": 99}") == null,
                "a future schema is a miss, not a wrong answer");
            require(ReflectionEngine.Report.fromJson("{\"schemaVersion\": 1, \"files\": 1}") == null,
                "a truncated entry is a miss, not a partial report");
            require(ReflectionEngine.Report.fromJson("not json at all") == null,
                "a corrupt entry is a miss");

            // It must not write where flixw lists installed plugin versions.
            require(!ResultCache.directory(work).startsWith(work.resolve("plugins")),
                "the cache stays out of <cache>/plugins, which flixw enumerates as versions");
            System.out.println("ResultCacheTest: ok");
        } finally {
            delete(work);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }
}
