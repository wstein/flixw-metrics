package dev.flixw.metrics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import dev.flixw.metrics.sdk.CompilerModel;

import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** Presentation-only projection of native JSON; measurement and policy are unchanged. */
    enum View {
        FULL, SUMMARY, FINDINGS, CHANGES;

        static View parse(String value) {
            return switch (value) {
                case "full" -> FULL;
                case "summary" -> SUMMARY;
                case "findings" -> FINDINGS;
                case "changes" -> CHANGES;
                default -> throw new Main.Usage("unknown view " + value
                    + " (expected full, summary, findings or changes)");
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
                  List<CompilerModel.DefInfo> defs, List<CompilerModel.ModuleInfo> modulesList,
                  List<CompilerModel.EffectInfo> effectDeclarations) {

        /**
         * The output schema, for consumers. It is deliberately not what the cache checks:
         * nothing reads this report back, so a schema change is a promise to a consumer rather
         * than a compatibility question for us. {@link Wire#VERSION} is the cache's own guard.
         */
        // A method so javac cannot inline yesterday's value into Baseline. Incremental builds
        // must ask the current report class which contract it emits.
        static int schemaVersion() {
            return 31;
        }

        /** A finding's physical span and compiler-level owner, ready for editor tooling. */
        record Location(String path, Integer startLine, Integer endLine, String logicalName,
                        String kind) {
            String json() {
                return "{\"path\": " + SourceMetrics.Smell.quote(path)
                    + ", \"startLine\": " + (startLine == null ? "null" : startLine)
                    + ", \"endLine\": " + (endLine == null ? "null" : endLine)
                    + ", \"logicalName\": " + SourceMetrics.Smell.quote(logicalName)
                    + ", \"kind\": " + SourceMetrics.Smell.quote(kind) + "}";
            }
        }

        Location location(SourceMetrics.Smell smell) {
            if (smell.file().isEmpty())
                return new Location("", null, null, smell.subject(), "module");
            CompilerModel.DefInfo owner = defs.stream()
                .filter(d -> d.file().equals(smell.file())
                    && (smell.subject().equals(d.name())
                        || smell.subject().startsWith(d.name() + ".")))
                .max(java.util.Comparator.comparingInt(d -> d.name().length()))
                .orElse(null);
            int start = smell.line() > 0 ? smell.line() : owner == null ? 1 : owner.line();
            int end = owner != null && start == owner.line()
                ? owner.line() + Math.max(1, owner.lines()) - 1 : start;
            return new Location(smell.file(), start, end, smell.subject(), "function");
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
            return render(format, p, config, comparison, View.FULL);
        }

        String render(Format format, Provenance p, MetricsConfig config,
                      Baseline.Comparison comparison, View view) {
            return render(format, p, config, comparison, view, PresentationFilter.none());
        }

        String render(Format format, Provenance p, MetricsConfig config,
                      Baseline.Comparison comparison, View view,
                      PresentationFilter filter) {
            if (view != View.FULL && format != Format.JSON)
                throw new IllegalArgumentException("compact views require JSON format");
            if (view == View.CHANGES && comparison == null)
                throw new IllegalArgumentException("changes view requires a baseline");
            Report presented = filter.active() ? withFindings(filter) : this;
            return switch (format) {
                case JSON -> presented.json(p, config, comparison, view, filter);
                case MARKDOWN -> Formats.markdown(presented, p, config, comparison);
                case SARIF -> Formats.sarif(presented, p, config, comparison);
                case TEXT -> presented.text(comparison);
            };
        }

        private Report withFindings(PresentationFilter filter) {
            List<SourceMetrics.Smell> selected = smells.stream().filter(filter::matches).toList();
            return new Report(files, modules, definitions, localDefinitions,
                effectfulDefinitions, cognitive, traits, instances, enums, structs, effects,
                typeAliases, lines, codeLines, commentLines, docCommentLines, blankLines,
                commentPercent, longestLine, linesOverLimit, datalogRules, datalogFacts,
                widestReturn, widestEffectSurface, widestDatalogDependencyBreadth,
                deepestDatalogDependency, mostRecursiveDatalogPredicates,
                longestFlixdocResultCharacters, tests, docCoveragePercent, purityPercent,
                excludedSources, selected, ranks, defs, modulesList, effectDeclarations);
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
                 + ", \"maxLocalParametersOwner\": "
                 + SourceMetrics.Smell.quote(d.maxLocalParametersOwner())
                 + ", \"maxLocalParametersLine\": " + d.maxLocalParametersLine()
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
                 + ", \"effects\": " + e.append(']')
                 + ", \"handlers\": " + d.handlers()
                 + ", \"handledOperations\": " + d.handledOperations()
                 + ", \"maxHandlerOperations\": " + d.maxHandlerOperations()
                 + ", \"resumptions\": " + d.resumptions() + "}";
        }

        private static String moduleJson(CompilerModel.ModuleInfo m) {
            return "{\"name\": " + SourceMetrics.Smell.quote(m.name())
                 + ", \"definitions\": " + m.definitions()
                 + ", \"lines\": " + m.lines()
                 + ", \"fanIn\": " + m.fanIn()
                 + ", \"fanOut\": " + m.fanOut()
                 + ", \"dependencies\": " + stringList(m.dependencies())
                 + ", \"dependents\": " + stringList(m.dependents())
                 + ", \"instability\": " + String.format(Locale.ROOT, "%.3f", m.instability()) + "}";
        }

        private static String effectJson(CompilerModel.EffectInfo effect) {
            StringBuilder operations = new StringBuilder("[");
            for (int i = 0; i < effect.operations().size(); i++) {
                if (i > 0) operations.append(", ");
                CompilerModel.EffectOperationInfo operation = effect.operations().get(i);
                operations.append("{\"name\": ")
                    .append(SourceMetrics.Smell.quote(operation.name()))
                    .append(", \"arity\": ").append(operation.arity()).append('}');
            }
            return "{\"name\": " + SourceMetrics.Smell.quote(effect.name())
                + ", \"file\": " + SourceMetrics.Smell.quote(effect.file())
                + ", \"line\": " + effect.line()
                + ", \"typeParameters\": " + effect.typeParameters()
                + ", \"operationCount\": " + effect.operationCount()
                + ", \"maxOperationArity\": " + effect.maxOperationArity()
                + ", \"operations\": " + operations.append(']') + "}";
        }

        private static String stringList(List<String> values) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(SourceMetrics.Smell.quote(values.get(i)));
            }
            return out.append(']').toString();
        }

        /** The same label/value pairs both renderers use; Markdown needs them too. */
        String[][] fieldsForRender() {
            return fields(false);
        }

        private static String ruleJson(RuleDefinitions.Rule rule, MetricsConfig config) {
            return "{\"id\": " + SourceMetrics.Smell.quote(rule.id())
                + ", \"title\": " + SourceMetrics.Smell.quote(rule.title())
                + ", \"category\": " + SourceMetrics.Smell.quote(rule.category())
                + ", \"severity\": " + SourceMetrics.Smell.quote(rule.level())
                + ", \"description\": " + SourceMetrics.Smell.quote(rule.description())
                + ", \"remediation\": " + SourceMetrics.Smell.quote(rule.advice())
                + ", \"unit\": " + SourceMetrics.Smell.quote(rule.unit())
                + ", \"categorical\": " + rule.categorical()
                + ", \"defaultLimit\": " + numberOrNull(rule.categorical(), rule.defaultLimit())
                + ", \"configuredLimit\": " + numberOrNull(rule.categorical(), config.limit(rule))
                + ", \"minimumCodeLines\": " + rule.minimumCodeLines()
                + ", \"enabled\": " + config.enabled(rule) + "}";
        }

        private static String numberOrNull(boolean absent, double number) {
            if (absent) return "null";
            return number == Math.rint(number) ? String.valueOf((long) number)
                : String.format(Locale.ROOT, "%.3f", number);
        }

        private String findingJson(SourceMetrics.Smell smell, String baselineState) {
            String flat = smell.json(baselineState);
            StringBuilder out = new StringBuilder(flat.substring(0, flat.length() - 1))
                .append(", \"location\": ").append(location(smell).json());
            if (smell.rule().equals("wide-coupling")) {
                modulesList.stream().filter(m -> m.name().equals(smell.subject())).findFirst()
                    .ifPresent(m -> out.append(", \"context\": {\"dependencies\": ")
                        .append(stringList(m.dependencies())).append(", \"dependents\": ")
                        .append(stringList(m.dependents())).append('}'));
            }
            return out.append('}').toString();
        }

        private static int compareFindings(SourceMetrics.Smell left,
                                           SourceMetrics.Smell right) {
            int severity = Integer.compare(
                RuleDefinitions.levelRank(RuleDefinitions.byId(right.rule()).level()),
                RuleDefinitions.levelRank(RuleDefinitions.byId(left.rule()).level()));
            if (severity != 0) return severity;
            int magnitude = Double.compare(right.overBy(), left.overBy());
            if (magnitude != 0) return magnitude;
            int file = left.file().compareTo(right.file());
            if (file != 0) return file;
            int line = Integer.compare(left.line(), right.line());
            return line != 0 ? line : left.rule().compareTo(right.rule());
        }

        private String json(Provenance p, MetricsConfig config, Baseline.Comparison comparison,
                            View view, PresentationFilter filter) {
            StringBuilder b = new StringBuilder("{\n");
            b.append("  \"$schema\": \"https://raw.githubusercontent.com/wstein/flixw-metrics/main/docs/metrics-report.schema.json\",\n");
            b.append("  \"schemaVersion\": ").append(schemaVersion()).append(",\n");
            if (filter.active())
                b.append("  \"presentationFilter\": ").append(filter.json()).append(",\n");
            if (p != null)
                b.append("  \"provenance\": ").append(p.json()).append(",\n");
            b.append("  \"configuration\": ").append(config.json()).append(",\n");
            if (comparison != null)
                b.append("  \"baseline\": ").append(comparison.json()).append(",\n");
            b.append("  \"ruleCatalog\": [");
            List<RuleDefinitions.Rule> rules = RuleDefinitions.all();
            for (int i = 0; i < rules.size(); i++) {
                b.append(i == 0 ? "\n" : ",\n").append("    ")
                    .append(ruleJson(rules.get(i), config));
            }
            b.append(rules.isEmpty() ? "],\n" : "\n  ],\n");
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
            b.append("  }");
            if (view == View.SUMMARY) return b.append("\n}\n").toString();
            if (view == View.FULL) {
                b.append(",\n  \"definitions\": [");
                for (int i = 0; i < defs.size(); i++) {
                    b.append(i == 0 ? "\n" : ",\n").append("    ").append(defJson(defs.get(i)));
                }
                b.append(defs.isEmpty() ? "]" : "\n  ]");
                b.append(",\n  \"effectDeclarations\": [");
                for (int i = 0; i < effectDeclarations.size(); i++) {
                    b.append(i == 0 ? "\n" : ",\n").append("    ")
                        .append(effectJson(effectDeclarations.get(i)));
                }
                b.append(effectDeclarations.isEmpty() ? "]" : "\n  ]");
                b.append(",\n  \"modules\": [");
                for (int i = 0; i < modulesList.size(); i++) {
                    b.append(i == 0 ? "\n" : ",\n").append("    ").append(moduleJson(modulesList.get(i)));
                }
                b.append(modulesList.isEmpty() ? "]" : "\n  ]");
                b.append(",\n  \"rankings\": [");
                Map<String, Integer> ordinals = new HashMap<>();
                for (int i = 0; i < ranks.size(); i++) {
                    Rankings.Rank rank = ranks.get(i);
                    int ordinal = ordinals.merge(rank.measure(), 1, Integer::sum);
                    b.append(i == 0 ? "\n" : ",\n").append("    ")
                        .append(rank.json(config, ordinal));
                }
                b.append(ranks.isEmpty() ? "]" : "\n  ]");
            }
            List<SourceMetrics.Smell> shown = view == View.CHANGES
                ? smells.stream().filter(s -> comparison.isAdded(s.id())
                    || comparison.isWorsened(s.id())).toList()
                : smells;
            if (view == View.FINDINGS || view == View.CHANGES)
                shown = shown.stream().sorted(Report::compareFindings).toList();
            b.append(",\n");
            b.append("  \"smells\": [");
            for (int i = 0; i < shown.size(); i++) {
                SourceMetrics.Smell smell = shown.get(i);
                String state = comparison == null ? null : comparison.isAdded(smell.id()) ? "new"
                    : comparison.isWorsened(smell.id()) ? "updated" : "unchanged";
                b.append(i == 0 ? "\n" : ",\n").append("    ")
                    .append(findingJson(smell, state));
            }
            b.append(shown.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
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
        // Compiler maps do not promise a useful iteration order. Keep the report stable so a
        // source-identical run does not manufacture a large JSON diff for an agent to inspect.
        List<CompilerModel.DefInfo> defs = m.defs().stream()
            .sorted(java.util.Comparator.comparing(CompilerModel.DefInfo::file)
                .thenComparingInt(CompilerModel.DefInfo::line)
                .thenComparing(CompilerModel.DefInfo::name))
            .toList();
        List<CompilerModel.ModuleInfo> modules = m.modules().stream()
            .sorted(java.util.Comparator.comparing(CompilerModel.ModuleInfo::name)).toList();
        List<CompilerModel.EffectInfo> effects = m.effectDeclarations().stream()
            .sorted(java.util.Comparator.comparing(CompilerModel.EffectInfo::file)
                .thenComparingInt(CompilerModel.EffectInfo::line)
                .thenComparing(CompilerModel.EffectInfo::name))
            .toList();
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
        List<CompilerModel.DefInfo> rankedDefs = defs.stream()
            .filter(d -> !config.isExcluded(d.file())).toList();
        List<SourceMetrics.Smell> smells = new java.util.ArrayList<>(text.smells());
        smells.addAll(Thresholds.apply(defs, modules, config));
        smells.removeIf(smell -> config.isSuppressed(smell) || config.isExcluded(smell.file()));
        smells.sort(java.util.Comparator.comparing(SourceMetrics.Smell::file)
            .thenComparingInt(SourceMetrics.Smell::line)
            .thenComparing(SourceMetrics.Smell::rule));
        return new Report(files, modules.size(), defs.size(), localDefs, effectful, cognitive,
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
            Rankings.of(rankedDefs, modules), defs, modules, effects);
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
