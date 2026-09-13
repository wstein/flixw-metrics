package dev.flixw.metrics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import dev.flixw.metrics.sdk.CompilerModel;

import java.util.List;
import java.util.Locale;

/** A deliberately small semantic metrics engine over Flix's runtime-checked typed root. */
final class Metrics {
    private Metrics() { }

    enum Format {
        TEXT, JSON, MARKDOWN, SARIF;

        static Format parse(String value) {
            return switch (value) {
                case "text" -> TEXT;
                case "json" -> JSON;
                case "md", "markdown" -> MARKDOWN;
                case "sarif" -> SARIF;
                default -> throw new Main.Usage("unknown format " + value
                    + " (expected text, json, md or sarif)");
            };
        }
    }

    /**
     * One report. Flat on purpose: every field is a count a consumer can compare between two
     * runs without knowing what the others mean.
     */
    record Report(int files, int modules, int definitions, int localDefinitions,
                  int effectfulDefinitions, int cognitive, int traits, int instances, int enums,
                  int structs, int effects, int typeAliases, int lines, int codeLines,
                  int commentLines, int docCommentLines, int blankLines, int commentPercent,
                  int longestLine, int linesOverLimit, int datalogRules, int datalogFacts,
                  int widestReturn, int widestEffectSurface,
                  int widestDatalogDependencyBreadth, int deepestDatalogDependency,
                  int mostRecursiveDatalogPredicates, int longestFlixdocResultCharacters,
                  int tests, int docCoveragePercent,
                  int purityPercent, List<MetricsConfig.ExcludedSource> excludedSources,
                  List<SourceMetrics.Smell> smells, List<Rankings.Rank> ranks,
                  List<CompilerModel.DefInfo> defs, List<CompilerModel.ModuleInfo> modulesList) {

        /**
         * The output schema, for consumers. It is deliberately not what the cache checks:
         * nothing reads this report back, so a schema change is a promise to a consumer rather
         * than a compatibility question for us. {@link Wire#VERSION} is the cache's own guard.
         */
        // A method so javac cannot inline yesterday's value into Baseline. Incremental builds
        // must ask the current report class which contract it emits.
        static int schemaVersion() {
            return 24;
        }


        String render(Format format) { return render(format, null, MetricsConfig.defaults()); }

        String render(Format format, Provenance p) {
            return render(format, p, MetricsConfig.defaults());
        }

        String render(Format format, Provenance p, MetricsConfig config) {
            return render(format, p, config, null);
        }

        String render(Format format, Provenance p, MetricsConfig config,
                      Baseline.Comparison comparison) {
            return switch (format) {
                case JSON -> json(p, config, comparison);
                case MARKDOWN -> Formats.markdown(this, p, config, comparison);
                case SARIF -> Formats.sarif(this, p, config, comparison);
                case TEXT -> text(comparison);
            };
        }

        /**
         * One definition, in full.
         *
         * <p>The aggregates say what a project is like and the rankings say where to start; this
         * is for the consumer that wants to ask its own question -- chart complexity per module,
         * diff two revisions, find every effectful definition without a doc comment. None of
         * those can be recovered from a total, and inventing a flag for each is a worse answer
         * than handing over what was measured.
         */
        private static String defJson(CompilerModel.DefInfo d) {
            StringBuilder e = new StringBuilder("[");
            for (int i = 0; i < d.effects().size(); i++) {
                if (i > 0) e.append(", ");
                e.append(SourceMetrics.Smell.quote(d.effects().get(i)));
            }
            StringBuilder parameterNames = new StringBuilder("[");
            for (int i = 0; i < d.formalParameterNames().size(); i++) {
                if (i > 0) parameterNames.append(", ");
                parameterNames.append(SourceMetrics.Smell.quote(d.formalParameterNames().get(i)));
            }
            StringBuilder datalogDependencies = new StringBuilder("[");
            for (int i = 0; i < d.datalogDependencies().size(); i++) {
                if (i > 0) datalogDependencies.append(", ");
                datalogDependencies.append(SourceMetrics.Smell.quote(
                    d.datalogDependencies().get(i)));
            }
            StringBuilder recursiveDatalogPredicates = new StringBuilder("[");
            for (int i = 0; i < d.recursiveDatalogPredicates().size(); i++) {
                if (i > 0) recursiveDatalogPredicates.append(", ");
                recursiveDatalogPredicates.append(SourceMetrics.Smell.quote(
                    d.recursiveDatalogPredicates().get(i)));
            }
            DocumentationMetrics.ParameterDocs parameterDocs =
                DocumentationMetrics.parameters(d.formalParameterNames(), d.docText());
            return "{\"name\": " + SourceMetrics.Smell.quote(d.name())
                 + ", \"module\": " + SourceMetrics.Smell.quote(d.module())
                 + ", \"file\": " + SourceMetrics.Smell.quote(d.file())
                 + ", \"line\": " + d.line() + ", \"lines\": " + d.lines()
                 + ", \"codeLines\": " + d.codeLines()
                 + ", \"parameters\": " + d.parameters()
                 + ", \"maxLocalParameters\": " + d.maxLocalParameters()
                 + ", \"localDefinitions\": " + d.localDefs()
                 + ", \"nesting\": " + d.nesting()
                 + ", \"cognitive\": " + d.cognitive()
                 + ", \"cognitiveDensity\": " + String.format(Locale.ROOT, "%.3f", d.cognitiveDensity())
                 + ", \"maxLineTokens\": " + d.maxLineTokens()
                 + ", \"maxLineTokensLine\": " + d.maxLineTokensLine()
                 + ", \"maxLineTokensOwner\": " + SourceMetrics.Smell.quote(d.maxLineTokensOwner())
                 + ", \"datalogRules\": " + d.datalogRules()
                 + ", \"datalogFacts\": " + d.datalogFacts()
                 + ", \"datalogDependencyBreadth\": " + d.datalogDependencyBreadth()
                 + ", \"datalogDependencies\": " + datalogDependencies.append(']')
                 + ", \"datalogDependencyDepth\": " + d.datalogDependencyDepth()
                 + ", \"recursiveDatalogPredicateCount\": "
                 + d.recursiveDatalogPredicateCount()
                 + ", \"recursiveDatalogPredicates\": "
                 + recursiveDatalogPredicates.append(']')
                 + ", \"returnWidth\": " + d.returnWidth()
                 + ", \"flixdocParameterCharacters\": " + d.flixdocParameterCharacters()
                 + ", \"flixdocResultCharacters\": " + d.flixdocResultCharacters()
                 + ", \"formalParameterNames\": " + parameterNames.append(']')
                 + ", \"parameterDocEntries\": " + parameterDocs.entries()
                 + ", \"redundantParameterDocEntries\": "
                 + parameterDocs.redundantEntries()
                 + ", \"isPublic\": " + d.isPublic()
                 + ", \"isTest\": " + d.isTest()
                 + ", \"hasDoc\": " + d.hasDoc()
                 + ", \"effectCount\": " + d.effectCount()
                 + ", \"effects\": " + e.append(']') + "}";
        }

        private static String moduleJson(CompilerModel.ModuleInfo m) {
            return "{\"name\": " + SourceMetrics.Smell.quote(m.name())
                 + ", \"definitions\": " + m.definitions()
                 + ", \"lines\": " + m.lines()
                 + ", \"fanIn\": " + m.fanIn()
                 + ", \"fanOut\": " + m.fanOut()
                 + ", \"instability\": " + String.format(Locale.ROOT, "%.3f", m.instability()) + "}";
        }

        /** The same label/value pairs both renderers use; Markdown needs them too. */
        String[][] fieldsForRender() {
            return fields(false);
        }

        private String json(Provenance p, MetricsConfig config, Baseline.Comparison comparison) {
            StringBuilder b = new StringBuilder("{\n");
            b.append("  \"schemaVersion\": ").append(schemaVersion()).append(",\n");
            if (p != null)
                b.append("  \"provenance\": ").append(p.json()).append(",\n");
            b.append("  \"configuration\": ").append(config.json()).append(",\n");
            if (comparison != null)
                b.append("  \"baseline\": ").append(comparison.json()).append(",\n");
            b.append("  \"excludedSources\": [");
            for (int i = 0; i < excludedSources.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(excludedSources.get(i).json());
            }
            b.append("],\n");
            // Nested, because two of the totals are named for things that also have lists --
            // `definitions` and `modules` -- and a flat object emitted both. JSON allows a
            // duplicate key and parsers keep the last, so the count was silently replaced by
            // the list and every consumer would have seen whichever the writer happened to
            // emit second. A summary object makes the collision impossible rather than
            // renaming one side and hoping the next field does not collide too.
            b.append("  \"summary\": {\n");
            String[][] fields = fields(true);
            for (int i = 0; i < fields.length; i++) {
                b.append("    \"").append(fields[i][0]).append("\": ")
                 .append(fields[i][1]).append(i == fields.length - 1 ? "\n" : ",\n");
            }
            b.append("  },\n");
            b.append("  \"definitions\": [");
            for (int i = 0; i < defs.size(); i++) {
                b.append(i == 0 ? "\n" : ",\n").append("    ").append(defJson(defs.get(i)));
            }
            b.append(defs.isEmpty() ? "],\n" : "\n  ],\n");
            b.append("  \"modules\": [");
            for (int i = 0; i < modulesList.size(); i++) {
                b.append(i == 0 ? "\n" : ",\n").append("    ").append(moduleJson(modulesList.get(i)));
            }
            b.append(modulesList.isEmpty() ? "],\n" : "\n  ],\n");
            b.append("  \"rankings\": [");
            for (int i = 0; i < ranks.size(); i++) {
                b.append(i == 0 ? "\n" : ",\n").append("    ").append(ranks.get(i).json());
            }
            b.append(ranks.isEmpty() ? "],\n" : "\n  ],\n");
            b.append("  \"smells\": [");
            for (int i = 0; i < smells.size(); i++) {
                b.append(i == 0 ? "\n" : ",\n").append("    ").append(smells.get(i).json());
            }
            b.append(smells.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
            return b.toString();
        }

        private String text(Baseline.Comparison comparison) {
            StringBuilder b = new StringBuilder();
            for (String[] pair : fields(false))
                b.append(pair[0]).append(": ").append(pair[1]).append('\n');
            if (!ranks.isEmpty()) {
                // Same reasoning as the Markdown heading (see Formats): a ranking is not a
                // finding, so the heading names what the table is rather than telling the
                // reader to act, and the note only appears when there is nothing else to act on.
                b.append('\n').append("where each measure peaks\n");
                if (smells.isEmpty())
                    b.append("(nothing crossed a threshold; these are just the current extremes)\n");
                b.append("(dense findings require at least ")
                    .append(RuleDefinitions.DENSE.minimumCodeLines())
                    .append(" code lines; shorter definitions may still rank)\n");
                for (Rankings.Rank r : ranks) b.append(r.text()).append('\n');
            }
            b.append('\n').append("smells: ").append(smells.size()).append('\n');
            for (SourceMetrics.Smell smell : smells) b.append(smell.text()).append('\n');
            if (!excludedSources.isEmpty()) {
                b.append('\n').append("excluded sources: ").append(excludedSources.size()).append('\n');
                for (MetricsConfig.ExcludedSource source : excludedSources)
                    b.append("  ").append(source.file()).append("  (")
                        .append(source.reason()).append(")\n");
            }
            if (comparison != null) b.append(comparison.text());
            return b.toString();
        }

        /** The order the two renderers share, so they cannot drift apart field by field. */
        private String[][] fields(boolean machine) {
            return new String[][] {
                {"files", "" + files}, {"analyzedFiles", "" + (files - excludedSources.size())},
                {"excludedFiles", "" + excludedSources.size()}, {"modules", "" + modules},
                {"definitions", "" + definitions}, {"localDefinitions", "" + localDefinitions},
                {"effectfulDefinitions", "" + effectfulDefinitions}, {"cognitive", "" + cognitive},
                {"traits", "" + traits}, {"instances", "" + instances}, {"enums", "" + enums},
                {"structs", "" + structs}, {"effects", "" + effects},
                {"typeAliases", "" + typeAliases}, {"lines", "" + lines},
                {"codeLines", "" + codeLines}, {"commentLines", "" + commentLines},
                {"docCommentLines", "" + docCommentLines}, {"blankLines", "" + blankLines},
                {"commentPercent", "" + commentPercent},
                {"longestLine", "" + longestLine}, {"linesOverLimit", "" + linesOverLimit},
                {"datalogRules", "" + datalogRules}, {"datalogFacts", "" + datalogFacts},
                {"widestReturn", "" + widestReturn},
                {"widestEffectSurface", "" + widestEffectSurface},
                {"widestDatalogDependencyBreadth", "" + widestDatalogDependencyBreadth},
                {"deepestDatalogDependency", "" + deepestDatalogDependency},
                {"mostRecursiveDatalogPredicates", "" + mostRecursiveDatalogPredicates},
                {"longestFlixdocResultCharacters", "" + longestFlixdocResultCharacters},
                {"tests", "" + tests},
                {"docCoveragePercent", percentage(docCoveragePercent, machine)},
                {"purityPercent", percentage(purityPercent, machine)},
            };
        }

        private static String percentage(int value, boolean machine) {
            return value < 0 ? machine ? "null" : "N/A" : String.valueOf(value);
        }





    }

    /**
     * Builds the report from what the adapter measured and what the text said.
     *
     * <p>Every derivation lives here rather than in the adapter, and that is the SDK boundary
     * doing its job: ratios, thresholds and findings are the same whichever compiler produced
     * the declarations, so writing them once means a second adapter inherits all of it.
     */
    static Report of(int files, CompilerModel.Model m, SourceMetrics text) {
        return of(files, m, text, MetricsConfig.defaults());
    }

    static Report of(int files, CompilerModel.Model m, SourceMetrics text, MetricsConfig config) {
        List<CompilerModel.DefInfo> defs = m.defs();
        CompilerModel.LineInfo lines = includedLines(m, config);
        int localDefs = defs.stream().mapToInt(CompilerModel.DefInfo::localDefs).sum();
        int effectful = (int) defs.stream().filter(d -> !d.isPure()).count();
        int cognitive = defs.stream().mapToInt(CompilerModel.DefInfo::cognitive).sum();
        int datalogRules = defs.stream().mapToInt(CompilerModel.DefInfo::datalogRules).sum();
        int datalogFacts = defs.stream().mapToInt(CompilerModel.DefInfo::datalogFacts).sum();
        int widestReturn = defs.stream().mapToInt(CompilerModel.DefInfo::returnWidth).max().orElse(0);
        int widestEffectSurface = defs.stream().mapToInt(CompilerModel.DefInfo::effectCount)
            .max().orElse(0);
        int widestDatalogDependencyBreadth = defs.stream()
            .mapToInt(CompilerModel.DefInfo::datalogDependencyBreadth).max().orElse(0);
        int deepestDatalogDependency = defs.stream()
            .mapToInt(CompilerModel.DefInfo::datalogDependencyDepth).max().orElse(0);
        int mostRecursiveDatalogPredicates = defs.stream()
            .mapToInt(CompilerModel.DefInfo::recursiveDatalogPredicateCount).max().orElse(0);
        int longestFlixdocResultCharacters = defs.stream()
            .filter(d -> d.isPublic() && !d.isTest() && !Thresholds.inTests(d.file()))
            .mapToInt(CompilerModel.DefInfo::flixdocResultCharacters).max().orElse(0);
        List<CompilerModel.DefInfo> api = defs.stream()
            .filter(d -> d.isPublic() && !d.isTest() && !Thresholds.inTests(d.file())
                && !config.isExcluded(d.file())).toList();
        List<SourceMetrics.Smell> smells = new java.util.ArrayList<>(text.smells());
        smells.addAll(Thresholds.apply(defs, m.modules(), config));
        smells.removeIf(smell -> config.isSuppressed(smell) || config.isExcluded(smell.file()));
        smells.sort(java.util.Comparator.comparing(SourceMetrics.Smell::file)
            .thenComparingInt(SourceMetrics.Smell::line)
            .thenComparing(SourceMetrics.Smell::rule));
        return new Report(files, m.modules().size(), defs.size(), localDefs, effectful, cognitive,
            m.traits(), m.instances(), m.enums(), m.structs(), m.effects(), m.typeAliases(),
            lines.total(), lines.code(), lines.comment(), lines.docComment(),
            lines.blank(), percent(lines.comment() + lines.docComment(), lines.total()),
            text.longestLine(), text.linesOverLimit(), datalogRules, datalogFacts, widestReturn,
            widestEffectSurface, widestDatalogDependencyBreadth, deepestDatalogDependency,
            mostRecursiveDatalogPredicates, longestFlixdocResultCharacters,
            (int) defs.stream().filter(CompilerModel.DefInfo::isTest).count(),
            percent(api.stream().filter(CompilerModel.DefInfo::hasDoc).count(), api.size()),
            percent(api.stream().filter(CompilerModel.DefInfo::isPure).count(), api.size()),
            text.excludedSources(), List.copyOf(smells),
            Rankings.of(defs, m.modules()).stream()
                .filter(rank -> !config.isExcluded(rank.file())).toList(), defs, m.modules());
    }

    private static CompilerModel.LineInfo includedLines(CompilerModel.Model model,
                                                         MetricsConfig config) {
        if (model.sources().isEmpty()) return model.lines();
        int total = 0;
        int code = 0;
        int comment = 0;
        int doc = 0;
        int blank = 0;
        for (CompilerModel.SourceInfo source : model.sources()) {
            if (config.isExcluded(source.file())) continue;
            total += source.lines().total();
            code += source.lines().code();
            comment += source.lines().comment();
            doc += source.lines().docComment();
            blank += source.lines().blank();
        }
        return new CompilerModel.LineInfo(total, code, comment, doc, blank);
    }

    /**
     * A share as a whole percent, and -1 for undefined when there is nothing to divide.
     *
     * <p>Integer percent because the report is compared between runs, and a ratio printed to
     * fifteen places turns every rounding difference into a change somebody has to read.
     */
    private static int percent(long part, int whole) {
        return whole == 0 ? -1 : (int) Math.round(100.0 * part / whole);
    }

    /** The project's own sources. Package-visible: {@link ResultCache} keys on this exact
     *  list, so computing it twice would be two chances to disagree about what a project is. */
    static List<Path> projectFiles(Path root) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        for (String directory : List.of("src", "test")) {
            Path base = root.resolve(directory);
            if (!Files.isDirectory(base)) continue;
            try (var paths = Files.walk(base)) {
                paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".flix"))
                    .sorted().forEach(files::add);
            }
        }
        if (files.isEmpty()) throw new Failure("no .flix files under " + root.resolve("src") + " or " + root.resolve("test"));
        return files;
    }

    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Failure(String message) { super(message); }
    }
}
