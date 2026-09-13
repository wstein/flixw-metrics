package dev.flixw.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

/** Proves that input changes retry once and never publish an unstable result. */
public final class StableInputsTest {
    private StableInputsTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("flixw-stable-inputs-");
        try {
            Files.createDirectories(root.resolve("src"));
            Files.writeString(root.resolve("src/Main.flix"), "def main(): Unit = ()\n");
            Main.Context context = new Main.Context(root, root.resolve("flix.jar"),
                Path.of("java"), root.resolve("cache"));
            ArrayDeque<String> once = new ArrayDeque<>(List.of("old", "changed", "new", "new"));
            int[] runs = {0};
            StableInputs.Result<Integer> retried = StableInputs.run(context, "test",
                (ignoredContext, ignoredSources, ignoredVersion) -> once.removeFirst(),
                (ignoredSources, ignoredDigest) -> ++runs[0]);
            require(runs[0] == 2 && retried.value() == 2 && retried.digest().equals("new")
                    && retried.retries() == 1,
                "one changing snapshot is discarded and retried");

            ArrayDeque<String> twice = new ArrayDeque<>(List.of("a", "b", "c", "d"));
            int[] published = {0};
            try {
                StableInputs.Result<Integer> unstable = StableInputs.run(context, "test",
                    (ignoredContext, ignoredSources, ignoredVersion) -> twice.removeFirst(),
                    (ignoredSources, ignoredDigest) -> 1);
                published[0] = unstable.value();
                throw new AssertionError("two changing snapshots must fail");
            } catch (Main.Usage e) {
                require(e.getMessage().contains("sources changed during measurement"),
                    "the diagnostic explains the unstable input");
            }
            require(published[0] == 0, "unstable work is never returned for publication");
            System.out.println("StableInputsTest: ok");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
