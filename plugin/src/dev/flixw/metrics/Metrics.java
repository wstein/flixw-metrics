package dev.flixw.metrics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import dev.flixw.metrics.sdk.CompilerModel;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                  int longestLine, int linesOverLimit, int tests, int docCoveragePercent,
                  int purityPercent, List<SourceMetrics.Smell> smells, List<Rankings.Rank> ranks) {

        static final int SCHEMA = 5;

        String render(Format format) {
            return switch (format) {
                case JSON -> json();
                case MARKDOWN -> Formats.markdown(this);
                case SARIF -> Formats.sarif(this);
                case TEXT -> text();
            };
        }

        /** The same label/value pairs both renderers use; Markdown needs them too. */
        String[][] fieldsForRender() {
            return fields();
        }

        private String json() {
            StringBuilder b = new StringBuilder("{\n");
            b.append("  \"schemaVersion\": ").append(SCHEMA).append(",\n");
            for (String[] pair : fields()) b.append("  \"").append(pair[0]).append("\": ")
                                            .append(pair[1]).append(",\n");
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

        private String text() {
            StringBuilder b = new StringBuilder();
            for (String[] pair : fields()) b.append(pair[0]).append(": ").append(pair[1]).append('\n');
            if (!ranks.isEmpty()) {
                b.append('\n').append("where to look first\n");
                for (Rankings.Rank r : ranks) b.append(r.text()).append('\n');
            }
            b.append('\n').append("smells: ").append(smells.size()).append('\n');
            for (SourceMetrics.Smell smell : smells) b.append(smell.text()).append('\n');
            return b.toString();
        }

        /** The order the two renderers share, so they cannot drift apart field by field. */
        private String[][] fields() {
            return new String[][] {
                {"files", "" + files}, {"modules", "" + modules},
                {"definitions", "" + definitions}, {"localDefinitions", "" + localDefinitions},
                {"effectfulDefinitions", "" + effectfulDefinitions}, {"cognitive", "" + cognitive},
                {"traits", "" + traits}, {"instances", "" + instances}, {"enums", "" + enums},
                {"structs", "" + structs}, {"effects", "" + effects},
                {"typeAliases", "" + typeAliases}, {"lines", "" + lines},
                {"codeLines", "" + codeLines}, {"commentLines", "" + commentLines},
                {"docCommentLines", "" + docCommentLines}, {"blankLines", "" + blankLines},
                {"commentPercent", "" + commentPercent},
                {"longestLine", "" + longestLine}, {"linesOverLimit", "" + linesOverLimit},
                {"tests", "" + tests}, {"docCoveragePercent", "" + docCoveragePercent},
                {"purityPercent", "" + purityPercent},
            };
        }

        /**
         * Reads back what {@link #json} wrote, or null if it is not that.
         *
         * <p>This parses one fixed shape this class emitted itself, which is why scanning for
         * {@code "name": <integer>} is enough and a JSON library would be a dependency bought
         * for nothing. Null on anything unexpected -- a truncated file, a hand-edited entry, a
         * {@code schemaVersion} this build does not know -- and the caller recomputes. A cache
         * entry is an optimisation, so the only wrong answer here is a confident one.
         *
         * <p>Smells are not read back. They are derived from the same inputs as the counts, so
         * an entry carrying counts without them would be a partial report; a cached entry is
         * therefore only reusable in full, and {@link #smellsFromJson} rebuilds the list.
         */
        static Report fromJson(String json) {
            Integer schema = field(json, "schemaVersion");
            if (schema == null || schema != SCHEMA) return null;
            int[] v = new int[23];
            String[] names = {"files", "modules", "definitions", "localDefinitions",
                "effectfulDefinitions", "cognitive", "traits", "instances", "enums", "structs",
                "effects", "typeAliases", "lines", "codeLines", "commentLines",
                "docCommentLines", "blankLines", "commentPercent", "longestLine",
                "linesOverLimit", "tests", "docCoveragePercent", "purityPercent"};
            for (int i = 0; i < names.length; i++) {
                Integer n = field(json, names[i]);
                if (n == null) return null;
                v[i] = n;
            }
            List<SourceMetrics.Smell> smells = smellsFromJson(json);
            if (smells == null) return null;
            List<Rankings.Rank> ranks = ranksFromJson(json);
            return new Report(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9],
                v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17], v[18], v[19], v[20],
                v[21], v[22], smells, ranks);
        }

        /** Same fixed shape as the smells above, and read back the same deliberately dumb way. */
        private static List<Rankings.Rank> ranksFromJson(String json) {
            List<Rankings.Rank> out = new ArrayList<>();
            Matcher m = Pattern.compile(
                "\\{\\s*\"measure\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"subject\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\","
              + "\\s*\"file\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"line\":\\s*(\\d+),"
              + "\\s*\"value\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}").matcher(json);
            while (m.find())
                out.add(new Rankings.Rank(unquote(m.group(1)), unquote(m.group(2)),
                    unquote(m.group(3)), Integer.parseInt(m.group(4)), unquote(m.group(5))));
            return out;
        }

        private static List<SourceMetrics.Smell> smellsFromJson(String json) {
            List<SourceMetrics.Smell> out = new ArrayList<>();
            Matcher m = Pattern.compile(
                "\\{\\s*\"rule\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"file\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\","
              + "\\s*\"line\":\\s*(\\d+),\\s*\"detail\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}")
                .matcher(json);
            while (m.find())
                out.add(new SourceMetrics.Smell(unquote(m.group(1)), unquote(m.group(2)),
                    Integer.parseInt(m.group(3)), unquote(m.group(4))));
            return out;
        }

        private static String unquote(String s) {
            return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r")
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }

        private static Integer field(String json, String name) {
            Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
            if (!m.find()) return null;
            try {
                return Integer.valueOf(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
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
        List<CompilerModel.DefInfo> defs = m.defs();
        int localDefs = defs.stream().mapToInt(CompilerModel.DefInfo::localDefs).sum();
        int effectful = (int) defs.stream().filter(d -> !d.isPure()).count();
        int cognitive = defs.stream().mapToInt(CompilerModel.DefInfo::cognitive).sum();
        List<CompilerModel.DefInfo> api = defs.stream()
            .filter(d -> d.isPublic() && !d.isTest()).toList();
        List<SourceMetrics.Smell> smells = new java.util.ArrayList<>(text.smells());
        smells.addAll(Thresholds.apply(defs, m.modules()));
        smells.sort(java.util.Comparator.comparing(SourceMetrics.Smell::file)
            .thenComparingInt(SourceMetrics.Smell::line)
            .thenComparing(SourceMetrics.Smell::rule));
        return new Report(files, m.modules().size(), defs.size(), localDefs, effectful, cognitive,
            m.traits(), m.instances(), m.enums(), m.structs(), m.effects(), m.typeAliases(),
            m.lines().total(), m.lines().code(), m.lines().comment(), m.lines().docComment(),
            m.lines().blank(), percent(m.lines().comment() + m.lines().docComment(),
                                       m.lines().total()),
            text.longestLine(), text.linesOverLimit(),
            (int) defs.stream().filter(CompilerModel.DefInfo::isTest).count(),
            percent(api.stream().filter(CompilerModel.DefInfo::hasDoc).count(), api.size()),
            percent(api.stream().filter(CompilerModel.DefInfo::isPure).count(), api.size()),
            List.copyOf(smells), Rankings.of(defs, m.modules()));
    }

    /**
     * A share as a whole percent, and 0 rather than undefined when there is nothing to divide.
     *
     * <p>Integer percent because the report is compared between runs, and a ratio printed to
     * fifteen places turns every rounding difference into a change somebody has to read.
     */
    private static int percent(long part, int whole) {
        return whole == 0 ? 0 : (int) Math.round(100.0 * part / whole);
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
