package dev.flixw.metrics;

import java.nio.file.Path;
import java.util.List;

/** Checks opt-in quality-gate parsing and severity semantics without launching a JVM. */
public final class MainOptionsTest {
    private MainOptionsTest() { }

    public static void main(String[] args) {
        require(Main.help(new String[] {"--help"}).contains("metrics capabilities"),
            "top-level help lists the available commands");
        require(Main.help(new String[] {"report", "--help"}).contains("--fail-on-new"),
            "report help documents report-only options");
        require(Main.help(new String[] {"init", "--help"}).contains("metrics init"),
            "init help is available without parsing report options");
        require(Main.help(new String[] {"capabilities", "--help"})
                .contains("compiler compatibility"),
            "capabilities help explains the command");
        require(Main.help(new String[] {"report"}) == null,
            "ordinary commands do not accidentally request help");
        Main.Options defaults = Main.parseOptions(new String[] {});
        require(defaults.format() == Metrics.Format.TEXT && defaults.failOn() == null
                && defaults.baseline() == null && defaults.failOnNew() == null
                && !defaults.init(),
            "the default remains report-only text");
        Main.Options init = Main.parseOptions(new String[] {"init"});
        require(init.init() && init.format() == Metrics.Format.JSON,
            "init selects native JSON for the captured baseline");
        expectUsage(new String[] {"init", "--format", "text"},
            "init accepts no options");
        Main.Options configured = Main.parseOptions(new String[] {
            "report", "--fail-on", "warning", "--format", "sarif",
            "--baseline", "metrics-baseline.json", "--fail-on-new", "error"
        });
        require(configured.format() == Metrics.Format.SARIF && "warning".equals(configured.failOn())
                && Path.of("metrics-baseline.json").equals(configured.baseline())
                && "error".equals(configured.failOnNew()),
            "format, absolute gate and baseline gate compose in any order");

        Metrics.Report warning = FormatsTest.reportWithSmells(List.of(new SourceMetrics.Smell(
            "deeply-nested", "A.f", "src/A.flix", 1, 5, 4, "", "levels")));
        require(configured.shouldFail(warning, null), "a warning crosses a warning gate");
        require(!Main.parseOptions(new String[] {"--fail-on", "error"}).shouldFail(warning, null),
            "a warning does not cross an error gate");
        require(!defaults.shouldFail(warning, null),
            "findings never fail without an explicit gate");
        Baseline.Comparison newWarning = new Baseline.Comparison(Path.of("baseline.json"),
            warning.smells(), List.of(), List.of(), 0);
        require(!configured.shouldFail(FormatsTest.reportWithSmells(List.of()), newWarning),
            "an error-only new-finding gate ignores a warning");
        require(Main.parseOptions(new String[] {"--baseline", "baseline.json",
                "--fail-on-new", "warning"}).shouldFail(
                    FormatsTest.reportWithSmells(List.of()), newWarning),
            "a new warning crosses a warning-only baseline gate");
        expectUsage(new String[] {"--fail-on-new", "warning"}, "requires --baseline");
        expectUsage(new String[] {"report", "--unknown"}, "unknown option --unknown");
        expectUsage(new String[] {"report", "--format"}, "--format requires a value");
        expectUsage(new String[] {"unknown"}, "unknown command or option unknown");
        for (String option : List.of("--format", "--fail-on", "--baseline", "--fail-on-new")) {
            String value = option.equals("--format") ? "json"
                : option.equals("--baseline") ? "baseline.json" : "warning";
            expectUsage(new String[] {option, value, option, value},
                "repeated option " + option);
        }
        Main.Context context = new Main.Context(Path.of("project"), Path.of("flix.jar"),
            Path.of("java-home/bin/java"), null);
        require(Main.bridgeCommand(context, new String[] {"report"}).contains("-Xss64m"),
            "the compiler bridge reserves the stack required by recursive compiler visitors");
        System.out.println("MainOptionsTest: ok");
    }

    private static void expectUsage(String[] args, String message) {
        try {
            Main.parseOptions(args);
            throw new AssertionError("expected usage error containing " + message);
        } catch (Main.Usage e) {
            require(e.getMessage().contains(message), "usage diagnostic names " + message);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
