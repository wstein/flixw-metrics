package dev.flixw.metrics;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The notice for a compiler release without a compatibility checkpoint. */
public final class CompilerVersionsTest {
    private CompilerVersionsTest() { }

    public static void main(String[] args) {
        List<String> verified = CompilerVersions.verified();
        require(verified.equals(List.of("0.60.0", "0.61.0", "0.65.0", "0.66.1", "0.67.1", "0.67.2",
                "0.68.0", "0.75.2", "0.75.3", "0.76.0", "0.76.1", "0.77.0", "0.78.0", "0.79.0")),
            "the packaged list is the verified releases, oldest first");
        require(!verified.contains("0.59.0") && !verified.contains("0.66.0"),
            "the rejected releases are not verified");

        require(warning("flix-0.79.0-8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf.jar")
                .isEmpty(), "a verified release in flixw's cache naming gets no notice");
        require(warning("flix-0.77.0.jar").isEmpty(), "a verified release by plain name gets no notice");

        String unknown = warning("flix-0.80.0-abcdef0123.jar").orElseThrow();
        require(unknown.startsWith("metrics: warning: ") && unknown.indexOf('\n') < 0,
            "the notice is a single warning line");
        require(unknown.contains("0.80.0") && unknown.contains("newest verified: 0.79.0")
                && unknown.contains("test-compiler-compatibility.sh"),
            "the notice names the release, the newest verified one and the checkpoint script");
        require(warning("flix-0.80.0.jar").orElseThrow().equals(unknown.replace("-abcdef0123", "")),
            "the cache suffix does not change the notice");
        require(CompilerVersions.newest(List.of("0.9.0", "0.10.0", "0.2.5")).equals("0.10.0"),
            "the newest release is chosen numerically, not as text");
        require(CompilerVersions.newest(List.of("0.79.0", "0.100.0", "0.80.0")).equals("0.100.0"),
            "a three-digit minor version is newer than a two-digit one");
        require(CompilerVersions.newest(List.of()).equals("none"), "no verified release is named none");
        require(warning("flix-0.100.0.jar").orElseThrow().contains("0.100.0 has no"),
            "a release with a longer minor version is judged as itself");
        require(warning("flix-0.59.0.jar").orElseThrow().contains("0.59.0"),
            "an unverified older release is noticed as well");

        // Names that only look like a release must not pass as a verified one.
        for (String name : List.of("xflix-0.79.0.jar", "flix-0.79.0-rc1.jar", "flix-0.79.0-ABCDEF.jar",
                "flix-00.79.0.jar", "flix-0.79.0.jar.bak", "flix-0.79.00.jar", "flix-0.7.0.jar")) {
            require(warning(name).isPresent(), name + " is not a verified release name");
        }
        require(warning("flix-0.79.0-ABCDEF.jar").orElseThrow().contains("cannot read"),
            "an upper-case digest suffix is not a cache name");

        String unreadable = warning("flix.jar").orElseThrow();
        require(unreadable.contains("cannot read the Flix version") && unreadable.indexOf('\n') < 0,
            "a jar name without a version gets a single notice that says so");
    }

    private static Optional<String> warning(String jarName) {
        return CompilerVersions.warning(Path.of("/cache/compilers", jarName));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
