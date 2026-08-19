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

            Path pluginCache = work.resolve("plugin-cache");
            Main.Context context = new Main.Context(project, jar, Path.of("java"), pluginCache);
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
            // Smells go through the same round trip as the counts. An entry that kept the
            // numbers and lost the findings would render as a clean project, which is the one
            // wrong answer this cache must never produce.
            Metrics.Report original = new Metrics.Report(1, 2, 3, 4, 5, 6, 7,
                8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
                List.of(new SourceMetrics.Smell("line-too-long", "src/A.flix", 12, "133 columns"),
                        new SourceMetrics.Smell("quoting", "src/\"odd\".flix", 1, "a \\ and a \" ")));
            ResultCache.write(pluginCache, base, original.render(Metrics.Format.JSON));
            Metrics.Report back = Metrics.Report.fromJson(
                ResultCache.read(pluginCache, base));
            require(back != null && back.files() == 1 && back.definitions() == 3
                && back.linesOverLimit() == 20 && back.purityPercent() == 23,
                "a written entry reads back intact");
            require(back.smells().size() == 2, "smells survive the round trip");
            require(back.smells().equals(original.smells()),
                "including a file name and detail that need escaping");
            require(ResultCache.read(pluginCache, "0".repeat(64)) == null, "an absent entry is a miss");
            require(Metrics.Report.fromJson("{\"schemaVersion\": 99}") == null,
                "a future schema is a miss, not a wrong answer");
            require(Metrics.Report.fromJson("{\"schemaVersion\": 1, \"files\": 1}") == null,
                "a truncated entry is a miss, not a partial report");
            require(Metrics.Report.fromJson("not json at all") == null,
                "a corrupt entry is a miss");

            // The directory is flixw's to name. A wrapper too old to set FLIXW_PLUGIN_CACHE
            // must leave the plugin uncached rather than have it invent a path flixw will
            // never collect.
            require(ResultCache.directory(context) == pluginCache, "flixw names the directory");
            Main.Context noCache = new Main.Context(project, jar, Path.of("java"), null);
            require(ResultCache.directory(noCache) == null, "an older wrapper means no cache");
            require(ResultCache.read(null, base) == null, "reads are a miss without a directory");
            ResultCache.write(null, base, "{}");
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
