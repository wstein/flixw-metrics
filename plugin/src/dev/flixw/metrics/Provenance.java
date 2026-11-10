package dev.flixw.metrics;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import dev.flixw.sdk.kit.ProvenanceData;

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
        ProvenanceData data = ProvenanceData.of(context.kit(), version, inputDigest);
        return new Provenance(data.commit(), data.dirty(), data.version(), data.when(),
            data.compilerArtifact(), data.inputDigest());
    }

    static boolean dirty(Path root) {
        return ProvenanceData.dirty(root);
    }

    static String command(Path root, Duration timeout, List<String> command) {
        return ProvenanceData.command(root, timeout, command);
    }
}
