package dev.flixw.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Flix releases that have a compatibility checkpoint, and the notice for any other release.
 *
 * <p>This is not admission. Whether the plugin can run against a compiler is decided only by the
 * bytecode-derived linkage gate. A compiler that links but has no checkpoint is still measured; this
 * class only says, once on stderr, that nobody has compared its results with a recorded release.
 *
 * <p>The list is the packaged resource {@code verified-flix-versions.txt}, the same file
 * {@code scripts/test-compiler-compatibility.sh} checks against its fetch and integration lines.
 * The release is read from the jar file name ({@code flix-<version>-<sha256>.jar} in flixw's compiler
 * cache), because the outer phase never initialises compiler classes. A renamed jar is therefore
 * judged by its name; the linkage gate still judges its contents.
 */
final class CompilerVersions {
    private static final String RESOURCE = "verified-flix-versions.txt";
    private static final Pattern JAR = Pattern.compile("flix-(\\d+\\.\\d+\\.\\d+)(?:-[0-9a-f]+)?\\.jar");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private CompilerVersions() { }

    /** The verified releases, oldest first, as listed in the packaged resource. */
    static List<String> verified() {
        try (InputStream in = CompilerVersions.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing resource " + RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .peek(line -> {
                    if (!VERSION.matcher(line).matches())
                        throw new IllegalStateException("bad version in " + RESOURCE + ": " + line);
                }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The single warning line for a compiler jar without a checkpoint, or empty when verified. */
    static Optional<String> warning(Path compilerJar) {
        String name = compilerJar.getFileName().toString();
        Matcher matcher = JAR.matcher(name);
        List<String> verified = verified();
        String newest = newest(verified);
        String pointer = "see scripts/test-compiler-compatibility.sh and docs/compiler-compatibility";
        if (!matcher.matches())
            return Optional.of("metrics: warning: cannot read the Flix version from the compiler jar name "
                + name + "; this release has no compatibility checkpoint (newest verified: " + newest
                + "), " + pointer);
        String version = matcher.group(1);
        if (verified.contains(version)) return Optional.empty();
        return Optional.of("metrics: warning: Flix " + version + " has no compatibility checkpoint (newest "
            + "verified: " + newest + "); results are not verified for it, " + pointer);
    }

    /** The numerically newest version of the list, or {@code none} when it is empty. */
    static String newest(List<String> versions) {
        return versions.stream().max(Comparator.comparing(CompilerVersions::key)).orElse("none");
    }

    private static long key(String version) {
        String[] parts = version.split("\\.");
        return (Long.parseLong(parts[0]) * 100_000L + Long.parseLong(parts[1])) * 100_000L
            + Long.parseLong(parts[2]);
    }
}
