package dev.flixw.metrics;

import java.util.List;

/** Checks opt-in quality-gate parsing and severity semantics without launching a JVM. */
public final class MainOptionsTest {
    private MainOptionsTest() { }

    public static void main(String[] args) {
        Main.Options defaults = Main.parseOptions(new String[] {});
        require(defaults.format() == Metrics.Format.TEXT && defaults.failOn() == null,
            "the default remains report-only text");
        Main.Options configured = Main.parseOptions(new String[] {
            "report", "--fail-on", "warning", "--format", "sarif"
        });
        require(configured.format() == Metrics.Format.SARIF && "warning".equals(configured.failOn()),
            "format and quality gate compose in either order");

        Metrics.Report warning = FormatsTest.reportWithSmells(List.of(new SourceMetrics.Smell(
            "deeply-nested", "A.f", "src/A.flix", 1, 5, 4, "", "levels")));
        require(configured.shouldFail(warning), "a warning crosses a warning gate");
        require(!Main.parseOptions(new String[] {"--fail-on", "error"}).shouldFail(warning),
            "a warning does not cross an error gate");
        require(!defaults.shouldFail(warning), "findings never fail without an explicit gate");
        System.out.println("MainOptionsTest: ok");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
