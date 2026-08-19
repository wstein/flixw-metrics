package dev.flixw.metrics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately small semantic metrics engine over Flix's runtime-checked typed root. */
final class Metrics {
    private Metrics() { }

    enum Format {
        TEXT, JSON;

        static Format parse(String value) {
            return switch (value) {
                case "text" -> TEXT;
                case "json" -> JSON;
                default -> throw new Main.Usage("unknown format " + value + " (expected text or json)");
            };
        }
    }

    /**
     * One report. Flat on purpose: every field is a count a consumer can compare between two
     * runs without knowing what the others mean.
     */
    record Report(int files, int modules, int definitions, int localDefinitions,
                  int effectfulDefinitions, int branches, int traits, int instances, int enums,
                  int structs, int effects, int typeAliases, int lines, int longestLine,
                  int linesOverLimit, List<SourceMetrics.Smell> smells) {

        static final int SCHEMA = 2;

        String render(Format format) {
            return format == Format.JSON ? json() : text();
        }

        private String json() {
            StringBuilder b = new StringBuilder("{\n");
            b.append("  \"schemaVersion\": ").append(SCHEMA).append(",\n");
            for (String[] pair : fields()) b.append("  \"").append(pair[0]).append("\": ")
                                            .append(pair[1]).append(",\n");
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
            b.append("smells: ").append(smells.size()).append('\n');
            for (SourceMetrics.Smell smell : smells) b.append(smell.text()).append('\n');
            return b.toString();
        }

        /** The order the two renderers share, so they cannot drift apart field by field. */
        private String[][] fields() {
            return new String[][] {
                {"files", "" + files}, {"modules", "" + modules},
                {"definitions", "" + definitions}, {"localDefinitions", "" + localDefinitions},
                {"effectfulDefinitions", "" + effectfulDefinitions}, {"branches", "" + branches},
                {"traits", "" + traits}, {"instances", "" + instances}, {"enums", "" + enums},
                {"structs", "" + structs}, {"effects", "" + effects},
                {"typeAliases", "" + typeAliases}, {"lines", "" + lines},
                {"longestLine", "" + longestLine}, {"linesOverLimit", "" + linesOverLimit},
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
            int[] v = new int[15];
            String[] names = {"files", "modules", "definitions", "localDefinitions",
                "effectfulDefinitions", "branches", "traits", "instances", "enums", "structs",
                "effects", "typeAliases", "lines", "longestLine", "linesOverLimit"};
            for (int i = 0; i < names.length; i++) {
                Integer n = field(json, names[i]);
                if (n == null) return null;
                v[i] = n;
            }
            List<SourceMetrics.Smell> smells = smellsFromJson(json);
            if (smells == null) return null;
            return new Report(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9],
                v[10], v[11], v[12], v[13], v[14], smells);
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
