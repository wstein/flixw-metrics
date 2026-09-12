package dev.flixw.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The measurements that come from the text, not from the compiler.
 *
 * <p>Line counts and line lengths need no typed AST, so they are taken from the files
 * directly. That is not a shortcut around the compiler-driven design -- it is the same
 * principle applied honestly. A line's length is a property of the text; routing it through
 * the type checker would not make it more true, and would make it unavailable whenever the
 * project does not compile, which is exactly when someone is most likely to be looking.
 *
 * <p>What is <em>not</em> done here is anything that needs meaning. Counting definitions by
 * scanning for {@code def} is the text-scan approach this plugin exists to avoid, and it gets
 * comments, strings and local definitions wrong. Those come from the compiler.
 */
record SourceMetrics(int lines, int longestLine, int linesOverLimit, List<Smell> smells) {

    /**
     * A hundred columns, matching what the compiler-side engine gated at.
     *
     * <p>The number is arbitrary in the way every such number is; what matters is that it is
     * stated once, applies everywhere, and is reported rather than enforced. This plugin
     * measures and names; it does not fail anybody's build.
     */
    static final int LINE_LIMIT = (int) RuleDefinitions.LINE_TOO_LONG.defaultLimit();

    static SourceMetrics measure(Path projectRoot, List<Path> sources) throws IOException {
        return measure(projectRoot, sources, MetricsConfig.defaults());
    }

    static SourceMetrics measure(Path projectRoot, List<Path> sources, MetricsConfig config)
            throws IOException {
        int lines = 0;
        int longest = 0;
        int over = 0;
        List<Smell> smells = new ArrayList<>();
        for (Path source : sources) {
            List<String> text = Files.readAllLines(source, StandardCharsets.UTF_8);
            lines += text.size();
            for (int i = 0; i < text.size(); i++) {
                // Tabs are counted as one column. Guessing a tab width would make the number
                // depend on a setting the file does not carry.
                int length = text.get(i).length();
                if (length > longest) longest = length;
                double lineLimit = config.limit(RuleDefinitions.LINE_TOO_LONG);
                if (config.enabled(RuleDefinitions.LINE_TOO_LONG) && length > lineLimit) {
                    over++;
                    smells.add(new Smell("line-too-long",
                        projectRoot.relativize(source).toString() + ":" + (i + 1),
                        projectRoot.relativize(source).toString(), i + 1,
                        length, lineLimit, "", RuleDefinitions.LINE_TOO_LONG.unit()));
                }
            }
        }
        return new SourceMetrics(lines, longest, over, List.copyOf(smells));
    }

    /**
     * One finding, in a shape a program can address.
     *
     * <p>An earlier version carried only a rule, a location and an English sentence. That reads
     * fine and is nearly useless to a consumer: sorting by severity, filtering by "more than
     * twice the limit", or charting a number over time all require parsing prose back into
     * figures somebody already had. So the measurement and the limit are fields, and the sentence
     * is derived from them rather than written alongside them -- which also means the two cannot
     * drift apart.
     *
     * @param subject what exceeded: a definition, a local, or a module
     * @param actual the measurement, as a double because some measures are ratios
     * @param limit what it was measured against
     * @param note extra context that is not a number, such as which local owns a line. Empty for
     *     most findings, and never load-bearing -- a consumer can ignore it entirely
     */
    record Smell(String rule, String subject, String file, int line, double actual, double limit,
                 String note, String unit) {

        /**
         * How far over the limit, as a multiple.
         *
         * <p>A multiple rather than a difference, so findings of different kinds order against
         * each other: 12 parameters against a limit of 5 and 180 lines against 60 are both 2.4x,
         * and "how bad is this" means the same thing for both.
         *
         * <p>A finding with no unit is categorical -- something is absent, and there is no
         * magnitude to be over by. Those score exactly 1: present in the list, ordered below
         * everything that actually exceeded a limit. Reporting a missing doc comment as "0 over
         * 1", which is what treating it as a magnitude produced, is both unreadable and sorts it
         * as the *least* severe by an accident of arithmetic rather than by a decision.
         */
        double overBy() {
            if (unit.isEmpty()) return 1.0;
            return limit == 0 ? actual : actual / limit;
        }

        /** Stable observation identity for baselines and SARIF result matching. */
        String id() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String identity = rule + '\0' + subject + '\0' + file.replace('\\', '/') + '\0' + line;
                byte[] bytes = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
                StringBuilder out = new StringBuilder(bytes.length * 2);
                for (byte value : bytes)
                    out.append(Character.forDigit((value >> 4) & 0xf, 16))
                       .append(Character.forDigit(value & 0xf, 16));
                return out.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError("the JVM has no SHA-256", e);
            }
        }

        /** Where it is, as a person writes it. Empty when nothing locates it. */
        String where() {
            return file.isEmpty() ? "" : file + ":" + line;
        }

        /** The sentence, derived so it cannot disagree with the numbers beside it. */
        String detail() {
            // No unit means nothing was exceeded; the note is the whole finding.
            if (unit.isEmpty()) return note;
            String base = number(actual) + " " + unit + ", over " + number(limit);
            return note.isEmpty() ? base : base + " (" + note + ")";
        }

        /** Whole numbers read as whole numbers; a ratio keeps one decimal. */
        private static String number(double value) {
            return value == Math.rint(value) ? String.valueOf((long) value)
                                             : String.format(Locale.ROOT, "%.1f", value);
        }

        String json() {
            return "{\"id\": " + quote(id())
                 + ", \"rule\": " + quote(rule)
                 + ", \"subject\": " + quote(subject)
                 + ", \"file\": " + quote(file)
                 + ", \"line\": " + line
                 + ", \"actual\": " + number(actual)
                 + ", \"limit\": " + number(limit)
                 + ", \"unit\": " + quote(unit)
                 + ", \"overBy\": " + String.format(Locale.ROOT, "%.2f", overBy())
                 + ", \"note\": " + quote(note)
                 + ", \"detail\": " + quote(detail()) + "}";
        }

        String text() {
            String at = where();
            return "  " + (at.isEmpty() ? subject : at) + "  " + rule + "  (" + detail()
                 + (at.isEmpty() ? "" : "  [" + subject + "]") + ")";
        }

        public static String quote(String value) {
            StringBuilder b = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            return b.append('"').toString();
        }
    }
}
