package dev.flixw.metrics;

import java.util.List;

/**
 * Checks the renderings meant for somebody other than the person at the terminal, plus the
 * ranking heading the terminal renderer shares with them.
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
            new SourceMetrics.Smell("deeply-nested", "A.deep", "src/A.flix", 12, 5, 4, "",
                "levels"),
            new SourceMetrics.Smell("wide-coupling", "Wide", "", 0, 20, 12, "",
                "modules depended on")));

        String sarif = report.render(Metrics.Format.SARIF);
        require(!sarif.contains("\"startLine\": 0"), "SARIF never emits a zero start line");
        require(sarif.contains("\"startLine\": 12"), "SARIF keeps a real line");
        require(count(sarif, "\"ruleId\"") == 2, "every finding becomes a result");
        // Declared even when unfired, so a consumer's rule list does not change per run.
        require(count(sarif, "\"id\": ") == 9, "every rule is declared, fired or not");
        require(sarif.trim().startsWith("{") && sarif.trim().endsWith("}"), "SARIF is one object");

        // Two totals share a name with a list -- `definitions` and `modules`. A flat object
        // emitted both, and JSON parsers keep the last, so the count was silently replaced.
        String json = report.render(Metrics.Format.JSON);
        require(count(json, "\"definitions\":") == 2 && json.contains("\"summary\": {"),
            "totals are nested, so a total cannot collide with a list of the same name");

        String md = report.render(Metrics.Format.MARKDOWN);
        require(md.startsWith("# Flix metrics"), "markdown has a title");
        require(md.contains("## Findings (2)"), "markdown counts the findings");
        require(md.indexOf("## Findings") < md.indexOf("## Totals"),
            "findings come before totals: the reader arrived with a question, not a census");
        require(md.contains("_Invert a condition"), "each rule carries an action");
        // 20/12 is 1.7x and 5/4 is 1.3x, so the worse group leads regardless of group size.
        require(md.indexOf("wide-coupling") < md.indexOf("deeply-nested"),
            "groups are ordered by their worst instance, not by how many they hold");
        require(md.contains("1.7x"), "each finding shows how far over it is");

        // The ranking heading names what the table is, not what to do about it -- a project
        // with findings still reads the extremes as plain fact, with no "nothing to act on"
        // caveat that would only make sense when nothing above found anything.
        require(md.contains("## Where each measure peaks"),
            "the ranking table is headed as an observation, not a to-do list");
        require(!md.contains("Nothing above crossed a threshold"),
            "a report with findings doesn't also claim there's nothing to act on");

        Metrics.Report clean = report(List.of());
        String cleanMd = clean.render(Metrics.Format.MARKDOWN);
        require(cleanMd.contains("No findings."),
            "a clean project says so rather than printing an empty heading");
        require(cleanMd.contains("## Where each measure peaks"),
            "the ranking table keeps its heading even when there are no findings to rank against");
        require(cleanMd.contains("Nothing above crossed a threshold"),
            "a clean report says the ranking below is not a to-do list, since 'No findings' alone"
                + " reads oddly next to a table that looks like one");
        require(!cleanMd.contains("Where to look first"),
            "the old actionable heading never reappears now that a ranking is not a finding");
        require(count(clean.render(Metrics.Format.SARIF), "\"ruleId\"") == 0,
            "a clean project produces a valid, empty result set");

        // The terminal renderer carries the same heading and the same reasoning: see Formats.
        String text = report.render(Metrics.Format.TEXT);
        require(text.contains("where each measure peaks"), "the terminal heading is an observation too");
        require(!text.contains("nothing crossed a threshold"),
            "a report with findings doesn't also claim there's nothing to act on");
        String cleanText = clean.render(Metrics.Format.TEXT);
        require(cleanText.contains("where each measure peaks")
                && cleanText.contains("nothing crossed a threshold"),
            "a clean terminal report notes the ranking below isn't a to-do list");
        require(!cleanText.contains("where to look first"),
            "the old actionable heading never reappears in the terminal format either");
        System.out.println("FormatsTest: ok");
    }

    private static Metrics.Report report(List<SourceMetrics.Smell> smells) {
        return new Metrics.Report(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            10, 8, 1, 1, 0, 10, 40, 0, 0, 0, 0, 1, 100, 100, smells,
            List.of(new Rankings.Rank("longest", "A.b", "src/A.flix", 3, "9 lines")),
            List.of(), List.of());
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
