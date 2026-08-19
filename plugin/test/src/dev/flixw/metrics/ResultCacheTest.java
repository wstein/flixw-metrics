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
            // The cache holds the compiler's *measurements*, not the report, so this is what
            // has to survive: a definition with everything set, and a symbol carrying the tab
            // the format itself uses to separate fields.
            var def = new dev.flixw.metrics.sdk.CompilerModel.DefInfo(
                "Foo.od\td", "Foo", "src/A.flix", 3, 40, 2, 9, 1, 4, 12, 31, 7, "Foo.odd.loop",
                2, 5, 6, true, false, true, List.of("IO", "Net"));
            var mod = new dev.flixw.metrics.sdk.CompilerModel.ModuleInfo("Foo", 1, 40, 2, 3);
            var model = new dev.flixw.metrics.sdk.CompilerModel.Model(List.of(def), List.of(mod),
                new dev.flixw.metrics.sdk.CompilerModel.LineInfo(40, 30, 4, 3, 3), 1, 2, 3, 4, 5, 6);
            ResultCache.write(pluginCache, base, Wire.encode(model));
            var back = Wire.decode(ResultCache.read(pluginCache, base));
            require(back != null, "a written entry reads back");
            require(back.defs().equals(model.defs()),
                "every definition field survives, including a tab inside a symbol");
            require(back.modules().equals(model.modules()), "modules survive");
            require(back.lines().equals(model.lines()), "line counts survive");
            require(back.traits() == 1 && back.typeAliases() == 6, "the counts survive");
            require(Wire.decode("v\t99\n") == null, "an unknown wire version is a miss");
            require(Wire.decode("not a record at all") == null, "a corrupt entry is a miss");
            require(Wire.decode("v\t" + Wire.VERSION + "\n") == null,
                "an entry missing its required records is a miss, not a partial model");
            require(ResultCache.read(pluginCache, "0".repeat(64)) == null, "an absent entry is a miss");

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
