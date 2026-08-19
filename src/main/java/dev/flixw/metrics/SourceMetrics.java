package dev.flixw.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    static final int LINE_LIMIT = 100;

    static SourceMetrics measure(Path projectRoot, List<Path> sources) throws IOException {
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
                if (length > LINE_LIMIT) {
                    over++;
                    smells.add(new Smell("line-too-long",
                        projectRoot.relativize(source).toString(), i + 1,
                        length + " columns, over " + LINE_LIMIT));
                }
            }
        }
        return new SourceMetrics(lines, longest, over, List.copyOf(smells));
    }

    /**
     * One finding, in a shape a program can address.
     *
     * <p>Rule name, file, line, and a human sentence -- deliberately the same four fields for
     * every rule, so a consumer can render or filter findings it has never heard of. A rule
     * added later must not require the reader to be updated first.
     */
    record Smell(String rule, String file, int line, String detail) {
        String json() {
            return "{\"rule\": " + quote(rule) + ", \"file\": " + quote(file)
                 + ", \"line\": " + line + ", \"detail\": " + quote(detail) + "}";
        }

        String text() {
            return "  " + file + ":" + line + "  " + rule + "  (" + detail + ")";
        }

        static String quote(String value) {
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
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            return b.append('"').toString();
        }
    }
}
