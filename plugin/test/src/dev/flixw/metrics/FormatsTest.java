package dev.flixw.metrics;

import java.util.List;

/**
 * Checks the two renderings meant for somebody other than the person at the terminal.
 *
 * <p>The case worth having a test for is the one a real project rarely produces: a module-level
 * finding, which has no file and no line. SARIF requires a region's line to be at least 1, so an
 * emitted `"startLine": 0` is a document a consumer rejects -- and it would only ever show up on
 * a project with wide coupling, which is not the project anyone tests against.
 */
public final class FormatsTest {
    private FormatsTest() { }

    public static void main(String[] args) {
        Metrics.Report report = report(List.of(
            new SourceMetrics.Smell("deeply-nested", "src/A.flix", 12, "5 levels"),
            new SourceMetrics.Smell("wide-coupling", "Wide", 0, "20 modules")));

        String sarif = report.render(Metrics.Format.SARIF);
        require(!sarif.contains("\"startLine\": 0"), "SARIF never emits a zero start line");
        require(sarif.contains("\"startLine\": 12"), "SARIF keeps a real line");
        require(count(sarif, "\"ruleId\"") == 2, "every finding becomes a result");
        // Declared even when unfired, so a consumer's rule list does not change per run.
        require(count(sarif, "\"id\": ") == 8, "every rule is declared, fired or not");
        require(sarif.trim().startsWith("{") && sarif.trim().endsWith("}"), "SARIF is one object");

        String md = report.render(Metrics.Format.MARKDOWN);
        require(md.startsWith("# Flix metrics"), "markdown has a title");
        require(md.contains("## Findings (2)"), "markdown counts the findings");
        require(md.indexOf("## Findings") < md.indexOf("## Totals"),
            "findings come before totals: the reader arrived with a question, not a census");
        require(md.contains("_Invert a condition"), "each rule carries an action");

        Metrics.Report clean = report(List.of());
        require(clean.render(Metrics.Format.MARKDOWN).contains("No findings."),
            "a clean project says so rather than printing an empty heading");
        require(count(clean.render(Metrics.Format.SARIF), "\"ruleId\"") == 0,
            "a clean project produces a valid, empty result set");
        System.out.println("FormatsTest: ok");
    }

    private static Metrics.Report report(List<SourceMetrics.Smell> smells) {
        return new Metrics.Report(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            10, 8, 1, 1, 0, 10, 40, 0, 0, 0, 0, 100, 100, smells,
            List.of(new Rankings.Rank("longest", "A.b", "src/A.flix", 3, "9 lines")));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
