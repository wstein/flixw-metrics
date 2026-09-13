package dev.flixw.metrics;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What a report was measured from, so it can be compared with another one.
 *
 * <p>A count without provenance is a snapshot and not a baseline: the same command on the
 * same project answers differently across a commit, an edit, or a threshold change, and a
 * reader holding two reports cannot tell which of those happened. The commit and the dirty
 * flag are the two that mislead most -- a report taken over a dirty tree describes a state
 * no commit contains and nobody can return to.
 */
record Provenance(String commit, boolean dirty, String version, String when,
                  String compilerArtifact, String inputDigest) {

    String json() {
        return "{\"commit\": " + SourceMetrics.Smell.quote(commit)
            + ", \"dirty\": " + dirty
            + ", \"analyzerVersion\": " + SourceMetrics.Smell.quote(version)
            + ", \"measuredAt\": " + SourceMetrics.Smell.quote(when)
            + ", \"compilerArtifact\": " + SourceMetrics.Smell.quote(compilerArtifact)
            + ", \"inputDigest\": " + SourceMetrics.Smell.quote(inputDigest) + "}";
    }

    static Provenance of(Main.Context context, String version, String inputDigest) {
        Path root = context.projectRoot();
        String sha = git(root, "rev-parse", "HEAD");
        boolean dirty = dirty(root);
        return new Provenance(sha.isEmpty() ? "(not a git checkout)" : sha, dirty, version,
            Instant.now().toString(), context.compilerJar().getFileName().toString(),
            inputDigest == null ? "(unavailable)" : inputDigest);
    }

    static boolean dirty(Path root) {
        return !git(root, "status", "--porcelain").isEmpty();
    }

    /**
     * One git command, or the empty string.
     *
     * <p>Not being in a checkout is an ordinary way to run this, so failure here is silence
     * rather than a diagnostic: a report is still worth having without a commit to pin it to,
     * and it says so in the header rather than refusing to be written.
     */
    private static String git(Path root, String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        return command(root, Duration.ofSeconds(10), command);
    }

    /** Runs a bounded command and reads its output only after it has exited successfully. */
    static String command(Path root, Duration timeout, List<String> command) {
        Path output = null;
        Process process = null;
        try {
            output = java.nio.file.Files.createTempFile("flixw-metrics-git-", ".out");
            process = new ProcessBuilder(command).directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return "";
            }
            if (process.exitValue() != 0) return "";
            return java.nio.file.Files.readString(output,
                java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            return "";
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (output != null) try {
                java.nio.file.Files.deleteIfExists(output);
            } catch (java.io.IOException ignored) {
                // Provenance is best-effort; scratch cleanup cannot make a report fail.
            }
        }
    }
}
