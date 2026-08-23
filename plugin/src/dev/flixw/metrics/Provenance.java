package dev.flixw.metrics;

import java.nio.file.Path;
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
record Provenance(String commit, boolean dirty, String version, String when) {

    static Provenance of(Path root, String version) {
        String sha = git(root, "rev-parse", "HEAD");
        boolean dirty = !git(root, "status", "--porcelain").isEmpty();
        return new Provenance(sha.isEmpty() ? "(not a git checkout)" : sha, dirty, version,
                              Instant.now().toString());
    }

    /**
     * One git command, or the empty string.
     *
     * <p>Not being in a checkout is an ordinary way to run this, so failure here is silence
     * rather than a diagnostic: a report is still worth having without a commit to pin it to,
     * and it says so in the header rather than refusing to be written.
     */
    private static String git(Path root, String... args) {
        try {
            List<String> cmd = new java.util.ArrayList<>(List.of("git"));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).directory(root.toFile())
                            .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : "";
        } catch (java.io.IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
