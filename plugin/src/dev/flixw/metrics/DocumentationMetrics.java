package dev.flixw.metrics;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Facts recoverable from Flix's free-form Markdown documentation. */
final class DocumentationMetrics {
    private DocumentationMetrics() { }

    // Intentionally one physical line: Markdown continuation lines need indentation-aware block
    // parsing, and attaching them with a regex would create confident false positives. Until a
    // block parser exists, wrapped descriptions are treated as unknown rather than redundant.
    private static final Pattern ITEM = Pattern.compile(
        "^\\s*[-*]\\s+`?([\\p{L}_][\\p{L}\\p{N}_']*)`?\\s*(?::|--|—)\\s*(.+?)\\s*$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_']+");
    private static final Set<String> BOILERPLATE = Set.of(
        "a", "an", "the", "given", "input", "argument", "parameter", "value", "function",
        "fn", "is", "named");

    record ParameterDocs(int entries, int redundantEntries) { }

    /**
     * Counts recognizable Markdown parameter-list entries and those that add only boilerplate.
     * Ordinary prose is deliberately invisible: an uncertain documentation lint is noise.
     */
    static ParameterDocs parameters(List<String> formalNames, String docText) {
        Set<String> names = new HashSet<>(formalNames);
        int entries = 0;
        int redundant = 0;
        for (String line : docText.split("\\R")) {
            Matcher item = ITEM.matcher(line);
            if (!item.matches() || !names.contains(item.group(1))) continue;
            entries++;
            if (isBoilerplate(item.group(1), item.group(2))) redundant++;
        }
        return new ParameterDocs(entries, redundant);
    }

    private static boolean isBoilerplate(String name, String description) {
        Matcher words = WORD.matcher(description.toLowerCase(Locale.ROOT));
        String normalizedName = name.toLowerCase(Locale.ROOT);
        boolean sawWord = false;
        while (words.find()) {
            sawWord = true;
            String word = words.group();
            if (!word.equals(normalizedName) && !BOILERPLATE.contains(word)) return false;
        }
        return sawWord;
    }
}
