package dev.flixw.metrics;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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
        localeIndependentNumbers();

        Metrics.Report report = report(List.of(
            new SourceMetrics.Smell("deeply-nested", "A.deep", "src/A.flix", 12, 5, 4, "",
                "levels"),
            new SourceMetrics.Smell("wide-coupling", "Wide", "", 0, 20, 12, "",
                "modules depended on")));

        String sarif = report.render(Metrics.Format.SARIF);
        require(!sarif.contains("\"startLine\": 0"), "SARIF never emits a zero start line");
        require(!sarif.contains("\"uri\": \"\""),
            "module findings do not invent an empty artifact URI");
        require(sarif.contains("\"startLine\": 12"), "SARIF keeps a real line");
        require(sarif.contains("\"logicalLocations\": [{\"fullyQualifiedName\": \"A.deep\""
                + ", \"kind\": \"function\"}]")
                && sarif.contains("\"fullyQualifiedName\": \"Wide\", \"kind\": \"module\""),
            "SARIF names both definition and module locations for agent navigation");
        require(count(sarif, "\"ruleId\"") == 2, "every finding becomes a result");
        // Declared even when unfired, so a consumer's rule list does not change per run.
        require(count(sarif, "\"id\": ") == 11, "every rule is declared, fired or not");
        require(sarif.trim().startsWith("{") && sarif.trim().endsWith("}"), "SARIF is one object");
        String firstId = report.smells().get(0).id();
        require(sarif.contains("\"partialFingerprints\": {\"flixwMetricsFinding/v1\": \""
                + firstId + "\"}"), "SARIF carries the stable finding fingerprint");

        // Two totals share a name with a list -- `definitions` and `modules`. A flat object
        // emitted both, and JSON parsers keep the last, so the count was silently replaced.
        String json = report.render(Metrics.Format.JSON);
        require(json.contains("\"$schema\": \"https://raw.githubusercontent.com/"
                + "wstein/flixw-metrics/main/docs/metrics-report.schema.json\""),
            "native JSON points consumers to its machine-readable contract");
        require(count(json, "\"definitions\":") == 2 && json.contains("\"summary\": {"),
            "totals are nested, so a total cannot collide with a list of the same name");
        require(json.contains("\"effectDeclarations\": [")
                && json.contains("\"name\": \"A.Console\"")
                && json.contains("\"typeParameters\": 1")
                && json.contains("\"operationCount\": 2")
                && json.contains("\"maxOperationArity\": 3")
                && json.contains("{\"name\": \"format\", \"arity\": 3}"),
            "native JSON exposes compiler-typed effect declaration and operation shape");
        require(json.contains("\"id\": \"" + firstId + "\""),
            "native JSON carries the same stable finding id as SARIF");
        require(json.contains("\"effectDetails\": ["),
            "native JSON carries instantiated effect details");
        require(json.contains("\"ruleCatalog\": [")
                && json.contains("\"title\": \"Deeply nested\"")
                && json.contains("\"category\": \"complexity\"")
                && json.contains("\"severity\": \"warning\"")
                && json.contains("\"remediation\": \"Invert a condition"),
            "native JSON gives agents a self-describing rule catalog");
        String findingsSection = json.substring(json.indexOf("\"smells\": ["));
        require(findingsSection.contains("\"rule\": \"deeply-nested\"")
                && findingsSection.contains("\"severity\": \"warning\""),
            "each native JSON finding carries its severity without a catalog join");
        String summaryJson = report.render(Metrics.Format.JSON, null,
            MetricsConfig.defaults(), null, Metrics.View.SUMMARY);
        require(summaryJson.contains("\"summary\": {")
                && summaryJson.contains("\"ruleCatalog\": [")
                && !summaryJson.contains("\"definitions\": [")
                && !summaryJson.contains("\"effectDeclarations\": [")
                && !summaryJson.contains("\"modules\": [")
                && !summaryJson.contains("\"rankings\": [")
                && !summaryJson.contains("\"smells\": ["),
            "summary view keeps context while omitting potentially large detail arrays");
        String findingsJson = report.render(Metrics.Format.JSON, null,
            MetricsConfig.defaults(), null, Metrics.View.FINDINGS);
        require(findingsJson.contains("\"smells\": [")
                && !findingsJson.contains("\"definitions\": [")
                && !findingsJson.contains("\"effectDeclarations\": [")
                && !findingsJson.contains("\"modules\": [")
                && !findingsJson.contains("\"rankings\": ["),
            "findings view is an actionable compact report");

        String md = report.render(Metrics.Format.MARKDOWN);
        require(md.startsWith("# Flix metrics"), "markdown has a title");
        require(md.contains("## Findings (2)"), "markdown counts the findings");
        require(md.indexOf("## Findings") < md.indexOf("## Totals"),
            "findings come before totals: the reader arrived with a question, not a census");
        require(md.contains("_Invert a condition"), "each rule carries an action");
        // 20/12 is 1.7x and 5/4 is 1.3x, so the worse group leads regardless of group size.
        require(md.indexOf("### `wide-coupling`") < md.indexOf("### `deeply-nested`"),
            "groups are ordered by their worst instance, not by how many they hold");
        require(md.contains("1.7x"), "each finding shows how far over it is");

        Metrics.Report priority = report(List.of(
            new SourceMetrics.Smell("line-too-long", "src/A.flix:1", "src/A.flix", 1,
                1000, 100, "", "UTF-16 code units"),
            new SourceMetrics.Smell("deeply-nested", "A.deep", "src/A.flix", 2,
                5, 4, "", "levels")));
        String priorityMd = priority.render(Metrics.Format.MARKDOWN);
        require(priorityMd.indexOf("### `deeply-nested`")
                < priorityMd.indexOf("### `line-too-long`"),
            "configured severity outranks incomparable threshold multiples");
        String priorityJson = priority.render(Metrics.Format.JSON, null,
            MetricsConfig.defaults(), null, Metrics.View.FINDINGS);
        require(priorityJson.indexOf("\"rule\": \"deeply-nested\"")
                < priorityJson.indexOf("\"rule\": \"line-too-long\""),
            "compact findings put the highest-severity work first");
        PresentationFilter onlyNested = PresentationFilter.of(
            "deeply-nested", "warning", "src/**");
        String filteredJson = priority.render(Metrics.Format.JSON, null,
            MetricsConfig.defaults(), null, Metrics.View.FINDINGS, onlyNested);
        String filteredFindings = filteredJson.substring(filteredJson.indexOf("\"smells\": ["));
        require(filteredFindings.contains("\"rule\": \"deeply-nested\"")
                && !filteredFindings.contains("\"rule\": \"line-too-long\""),
            "native JSON filters current findings by rule, minimum severity and file glob");
        require(filteredJson.contains("\"presentationFilter\": {\"rule\":"
                + " \"deeply-nested\", \"minimumSeverity\": \"warning\","
                + " \"file\": \"src/**\"}"),
            "saved JSON identifies that it is a context-reduced presentation");
        require(priority.smells().size() == 2,
            "render filtering does not mutate the measured report");
        require(!priority.render(Metrics.Format.SARIF, null, MetricsConfig.defaults(), null,
                Metrics.View.FULL, PresentationFilter.of(null, null, "test/**"))
                .contains("\"ruleId\": "),
            "SARIF applies the same presentation-only file filter");

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
        require(cleanMd.contains("Dense findings require at least 4 code lines"),
            "Markdown explains why a tiny densest definition may not be a finding");
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
        require(cleanText.contains("dense findings require at least 4 code lines"),
            "terminal output explains the dense eligibility floor beside its rankings");
        require(!cleanText.contains("where to look first"),
            "the old actionable heading never reappears in the terminal format either");

        String reason = "requires at least 4 code lines (has 3)";
        Metrics.Report ineligible = reportWithRank(new Rankings.Rank("densest", "A.tiny",
            "src/A.flix", 7, "3.0 complexity/line", false, reason));
        String ineligibleJson = ineligible.render(Metrics.Format.JSON);
        require(ineligibleJson.contains("\"eligible\": false")
                && ineligibleJson.contains("\"ineligibilityReason\": \"" + reason + "\""),
            "native JSON marks the individual ranking and gives its reason");
        require(ineligible.render(Metrics.Format.TEXT).contains("ineligible: " + reason),
            "terminal output marks the individual ranking, not only the report");
        String ineligibleMd = ineligible.render(Metrics.Format.MARKDOWN);
        require(ineligibleMd.contains("| ineligible — " + reason + " |"),
            "Markdown marks the individual ranking, not only the report");

        Provenance provenance = new Provenance("abc123", true, "1.2.3", "2026-09-12T10:00:00Z",
            "flix.jar", "inputs123");
        String provenJson = report.render(Metrics.Format.JSON, provenance);
        require(provenJson.contains("\"provenance\"") && provenJson.contains("\"commit\": \"abc123\"")
                && provenJson.contains("\"analyzerVersion\": \"1.2.3\"")
                && provenJson.contains("\"compilerArtifact\": \"flix.jar\"")
                && provenJson.contains("\"inputDigest\": \"inputs123\""),
            "JSON identifies the source revision and analyzer that produced it");
        require(provenJson.contains("\"minimumCodeLines\": 4"),
            "native JSON exposes the dense eligibility floor to consumers");
        String provenSarif = report.render(Metrics.Format.SARIF, provenance);
        require(provenSarif.contains("\"version\": \"1.2.3\"")
                && provenSarif.contains("\"commit\": \"abc123\""),
            "SARIF identifies the source revision and analyzer that produced it");

        SourceMetrics.Smell added = report.smells().get(0);
        SourceMetrics.Smell updated = report.smells().get(1);
        Baseline.Snapshot before = new Baseline.Snapshot(updated.id(), updated.rule(),
            updated.subject(), updated.file(), updated.line(), 13, 12, updated.unit(), 1.08);
        Baseline.Snapshot resolved = new Baseline.Snapshot("gone", "dense", "A.gone",
            "src/A.flix", 40, 1.2, 1, "complexity per line", 1.2);
        Baseline.Comparison comparison = new Baseline.Comparison(Path.of("baseline.json"),
            List.of(added), List.of(new Baseline.Change(before, updated)), List.of(resolved), 3);
        String comparedJson = report.render(Metrics.Format.JSON, provenance,
            MetricsConfig.defaults(), comparison);
        require(comparedJson.contains("\"baseline\": {")
                && comparedJson.contains("\"newCount\": 1")
                && comparedJson.contains("\"resolvedCount\": 1"),
            "native JSON reports every baseline outcome separately");
        require(comparedJson.contains("\"baselineState\": \"new\"")
                && comparedJson.contains("\"baselineState\": \"updated\""),
            "native JSON labels current findings with their baseline state");
        String changesJson = report.render(Metrics.Format.JSON, provenance,
            MetricsConfig.defaults(), comparison, Metrics.View.CHANGES);
        require(changesJson.contains("\"baselineState\": \"new\"")
                && changesJson.contains("\"baselineState\": \"updated\"")
                && !changesJson.contains("\"baselineState\": \"unchanged\"")
                && !changesJson.contains("\"definitions\": [")
                && !changesJson.contains("\"rankings\": ["),
            "changes view contains only new and worsened current findings");
        String comparedText = report.render(Metrics.Format.TEXT, provenance,
            MetricsConfig.defaults(), comparison);
        require(comparedText.contains("baseline: 1 new, 1 worsened, 1 resolved, 3 retained"),
            "terminal output summarizes the baseline delta");
        String comparedMd = report.render(Metrics.Format.MARKDOWN, provenance,
            MetricsConfig.defaults(), comparison);
        require(comparedMd.contains("## Baseline changes") && comparedMd.contains("Resolved: 1")
                && comparedMd.contains("`A.gone` — `dense`"),
            "Markdown separates baseline changes from current findings");
        String comparedSarif = report.render(Metrics.Format.SARIF, provenance,
            MetricsConfig.defaults(), comparison);
        require(comparedSarif.contains("\"baselineState\": \"new\"")
                && comparedSarif.contains("\"baselineState\": \"updated\""),
            "SARIF labels new and worsened current results");
        System.out.println("FormatsTest: ok");
    }

    private static void localeIndependentNumbers() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var def = dev.flixw.metrics.sdk.CompilerModel.DefInfo.builder(
                    "A.f", "A", "src/A.flix", 1)
                .lines(2).codeLines(2).nesting(1).cognitive(3).hasDoc(true).build();
            var module = new dev.flixw.metrics.sdk.CompilerModel.ModuleInfo("A", 1, 2, 1, 2,
                List.of("B", "C"), List.of("Foundation"));
            Metrics.Report localized = new Metrics.Report(1, 1, 1, 0, 0, 3, 0, 0, 0,
                0, 0, 0, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(
                    new SourceMetrics.Smell("dense", "A.f", "src/A.flix", 1,
                        1.5, 1.0, "", "complexity per line"),
                    new SourceMetrics.Smell("wide-coupling", "A", "", 0,
                        2, 1, "", "modules depended on")),
                List.of(new Rankings.Rank("densest", "A.f", "src/A.flix", 1,
                    String.format(Locale.ROOT, "%.1f complexity/line", 1.5))),
                List.of(def), List.of(module), List.of());

            String json = localized.render(Metrics.Format.JSON);
            require(json.contains("\"cognitiveDensity\": 1.500"),
                "JSON decimals are independent of the process locale");
            require(json.contains("\"instability\": 0.667"),
                "module JSON decimals are independent of the process locale");
            require(json.contains("\"dependencies\": [\"B\", \"C\"]")
                    && json.contains("\"dependents\": [\"Foundation\"]"),
                "module JSON names coupling edges needed to plan a refactor");
            require(json.contains("\"context\": {\"dependencies\": [\"B\", \"C\"],"
                    + " \"dependents\": [\"Foundation\"]}"),
                "a compact coupling finding carries the names needed to plan the edit");
            require(json.contains("\"location\": {\"path\": \"src/A.flix\","
                    + " \"startLine\": 1, \"endLine\": 2,"
                    + " \"logicalName\": \"A.f\", \"kind\": \"function\"}"),
                "native findings expose an edit-ready definition span");
            require(localized.render(Metrics.Format.SARIF).contains(
                    "\"region\": {\"startLine\": 1, \"endLine\": 2}"),
                "SARIF findings expose the full measured definition span");
            require(json.contains("\"overBy\": 1.50"),
                "finding JSON decimals are independent of the process locale");
            require(json.contains("\"handlers\": 0")
                    && json.contains("\"handledOperations\": 0")
                    && json.contains("\"maxHandlerOperations\": 0")
                    && json.contains("\"resumptions\": 0"),
                "native definitions expose additive effect-handler measurements");
            require(localized.render(Metrics.Format.MARKDOWN).contains("1.5x"),
                "Markdown decimals are independent of the process locale");
        } finally {
            Locale.setDefault(previous);
        }
    }

    static Metrics.Report reportForOtherTests() {
        return report(List.of());
    }

    static Metrics.Report reportWithSmells(List<SourceMetrics.Smell> smells) {
        return report(smells);
    }

    private static Metrics.Report report(List<SourceMetrics.Smell> smells) {
        return reportWithRank(new Rankings.Rank("longest", "A.b", "src/A.flix", 3,
            "9 lines"), smells);
    }

    private static Metrics.Report reportWithRank(Rankings.Rank rank) {
        return reportWithRank(rank, List.of());
    }

    private static Metrics.Report reportWithRank(Rankings.Rank rank,
                                                 List<SourceMetrics.Smell> smells) {
        var definition = dev.flixw.metrics.sdk.CompilerModel.DefInfo.builder(
                "A.b", "A", "src/A.flix", 3)
            .effectDetails(List.of(
                new dev.flixw.metrics.sdk.CompilerModel.DefInfo.EffectDetail(
                    "State", List.of("Int32"))))
            .build();
        return new Metrics.Report(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            10, 8, 1, 1, 0, 10, 40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 100, 100,
            List.of(), smells,
            List.of(rank), List.of(definition), List.of(), List.of(
                new dev.flixw.metrics.sdk.CompilerModel.EffectInfo(
                    "A.Console", "src/A.flix", 2, 1, List.of(
                        new dev.flixw.metrics.sdk.CompilerModel.EffectOperationInfo("print", 1),
                        new dev.flixw.metrics.sdk.CompilerModel.EffectOperationInfo("format", 3)))));
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
