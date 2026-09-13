package dev.flixw.metrics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import dev.flixw.metrics.sdk.CompilerModel;

/** Runs measurement work only against an input snapshot that remains unchanged. */
final class StableInputs {
    private StableInputs() { }

    @FunctionalInterface
    interface Digester {
        String digest(Main.Context context, List<Path> sources, String version);
    }

    @FunctionalInterface
    interface Work<T> {
        T run(List<Path> sources, String digest) throws IOException, CompilerModel.ModelFailure;
    }

    record Result<T>(T value, String digest, int retries) { }

    static <T> Result<T> run(Main.Context context, String version, Digester digester, Work<T> work)
            throws IOException, CompilerModel.ModelFailure {
        for (int attempt = 0; attempt < 2; attempt++) {
            List<Path> beforeSources = Metrics.projectFiles(context.projectRoot());
            String before = digester.digest(context, beforeSources, version);
            T value = work.run(beforeSources, before);
            List<Path> afterSources = Metrics.projectFiles(context.projectRoot());
            String after = digester.digest(context, afterSources, version);
            if (Objects.equals(before, after)) return new Result<>(value, before, attempt);
        }
        throw new Main.Usage("sources changed during measurement; retry when edits have settled");
    }
}
