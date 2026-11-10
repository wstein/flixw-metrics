package dev.flixw.metrics;

import dev.flixw.sdk.bridge.CompilerModel;
import dev.flixw.sdk.bridge.CompilerModel.LineInfo;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared real-compiler comment-category regression for every adapter family. */
final class CommentKindChecks {
    private CommentKindChecks() { }

    static void verify(CompilerModel adapter, Path fixture) throws Exception {
        Path project = Files.createTempDirectory("flixw-comment-kinds-");
        try {
            Files.copy(fixture.resolve("flix.toml"), project.resolve("flix.toml"));
            Flix0753AdapterTest.adaptManifest(project);
            Files.createDirectories(project.resolve("src"));
            Files.writeString(project.resolve("src/IssueBanner.flix"), """
                //// Ordinary banner, not documentation.
                /// Actual documentation.
                pub def issueBanner(): Int32 = 1
                """);
            Files.writeString(project.resolve("src/SlashRuns.flix"), """
                // Ordinary line.
                //// Four slashes.
                ///// Five slashes.
                //////////////// Longer banner.
                /// Actual documentation.
                pub def slashRuns(): Int32 = 1
                """);
            Files.writeString(project.resolve("src/Mixed.flix"),
                "// Ordinary line.\r\n/* Block comment.\r\n * /// Block content.\r\n */\r\n"
                    + "/// Actual documentation.\r\npub def mixed(): Int32 = 1 // Inline.\r\n\r\n");
            var model = adapter.measure(project);
            require(model.defs().size() == 3, "all comment probes compile");
            check(model, "IssueBanner.flix", new LineInfo(3, 1, 1, 1, 0));
            check(model, "SlashRuns.flix", new LineInfo(6, 1, 4, 1, 0));
            check(model, "Mixed.flix", new LineInfo(7, 1, 4, 1, 1));
        } finally {
            Flix0753AdapterTest.delete(project);
        }
    }

    private static void check(CompilerModel.Model model, String file, LineInfo expected) {
        var actual = model.sources().stream().filter(source -> source.file().endsWith(file))
            .findFirst().orElseThrow().lines();
        require(actual.equals(expected), file + " comment token categories: expected "
            + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
