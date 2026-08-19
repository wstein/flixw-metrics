package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.LineInfo;
import dev.flixw.metrics.sdk.CompilerModel.Model;
import dev.flixw.metrics.sdk.CompilerModel.ModuleInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * How the compiler's measurements are written to the cache and read back.
 *
 * <p><b>What is cached is the {@link Model}, not the report.</b> An earlier version cached the
 * finished report and re-parsed its own JSON, which was wrong twice over. It serialised derived
 * data — thresholds, rankings, formatted sentences — that costs microseconds to recompute, next
 * to the ~3.4s of compiler work that is the only thing worth avoiding. And it meant a cached
 * entry carried yesterday's findings: edit a threshold without bumping the plugin version and
 * every hit would serve conclusions drawn under the old limit.
 *
 * <p>Caching the measurements instead means findings, rankings and every format are recomputed
 * on each run from data that cannot go stale, because it is a fact about the source rather than
 * a judgement about it. The reference implementation in the Flix fork does not cache at all and
 * so never faced this; it computes and renders in one direction, which is the shape this now
 * matches on a cache hit.
 *
 * <p>Deliberately not JSON. This format is ours, is read only by this class, and is built for
 * one job: round-tripping without ambiguity. One record per line, a tag, then tab-separated
 * fields. Adding a field is appending one, not extending a regex.
 *
 * <p>Values are escaped, which is load-bearing rather than defensive: a Flix symbol can hold
 * almost anything, and an unescaped tab would shift every field after it — read back as a
 * different measurement rather than as a broken one.
 */
final class Wire {
    private Wire() { }

    /** Bumped when a record's field order changes; a mismatch is a cache miss, never a guess. */
    static final int VERSION = 1;

    static String encode(Model m) {
        StringBuilder b = new StringBuilder();
        b.append("v\t").append(VERSION).append('\n');
        row(b, "l", m.lines().total(), m.lines().code(), m.lines().comment(),
            m.lines().docComment(), m.lines().blank());
        row(b, "c", m.traits(), m.instances(), m.enums(), m.structs(), m.effects(),
            m.typeAliases());
        for (DefInfo d : m.defs()) {
            row(b, "d", d.name(), d.module(), d.file(), d.line(), d.lines(), d.parameters(),
                d.maxLocalParameters(), d.localDefs(), d.nesting(), d.cognitive(),
                d.maxLineTokens(), d.maxLineTokensLine(), d.maxLineTokensOwner(),
                d.datalogRules(), d.datalogFacts(), d.returnWidth(), d.isPublic(), d.isTest(),
                d.hasDoc(), String.join(",", d.effects()));
        }
        for (ModuleInfo mi : m.modules()) {
            row(b, "m", mi.name(), mi.definitions(), mi.lines(), mi.fanIn(), mi.fanOut());
        }
        return b.toString();
    }

    /**
     * Reads back what {@link #encode} wrote, or null if it is not that.
     *
     * <p>Null on anything unexpected — a truncated file, a hand-edited entry, a version this
     * build does not know — and the caller recomputes. A cache entry is an optimisation, so the
     * only wrong answer here is a confident one.
     */
    static Model decode(String text) {
        try {
            List<String[]> rows = new ArrayList<>();
            for (String line : text.split("\n")) {
                if (!line.isEmpty()) rows.add(line.split("\t", -1));
            }
            if (rows.isEmpty() || !rows.get(0)[0].equals("v")) return null;
            if (Integer.parseInt(rows.get(0)[1]) != VERSION) return null;

            LineInfo lines = null;
            int[] counts = null;
            List<DefInfo> defs = new ArrayList<>();
            List<ModuleInfo> modules = new ArrayList<>();

            for (String[] f : rows.subList(1, rows.size())) {
                switch (f[0]) {
                    case "l" -> lines = new LineInfo(i(f[1]), i(f[2]), i(f[3]), i(f[4]), i(f[5]));
                    case "c" -> counts = new int[] {i(f[1]), i(f[2]), i(f[3]), i(f[4]), i(f[5]),
                        i(f[6])};
                    case "d" -> defs.add(new DefInfo(un(f[1]), un(f[2]), un(f[3]), i(f[4]),
                        i(f[5]), i(f[6]), i(f[7]), i(f[8]), i(f[9]), i(f[10]), i(f[11]), i(f[12]),
                        un(f[13]), i(f[14]), i(f[15]), i(f[16]), b(f[17]), b(f[18]), b(f[19]),
                        un(f[20]).isEmpty() ? List.of() : List.of(un(f[20]).split(","))));
                    case "m" -> modules.add(new ModuleInfo(un(f[1]), i(f[2]), i(f[3]), i(f[4]),
                        i(f[5])));
                    default -> { }
                }
            }
            if (lines == null || counts == null) return null;
            return new Model(defs, modules, lines, counts[0], counts[1], counts[2], counts[3],
                counts[4], counts[5]);
        } catch (RuntimeException e) {
            // Any malformed entry at all: a miss, not an exception thrown at a user who only
            // asked for their metrics.
            return null;
        }
    }

    private static void row(StringBuilder b, String tag, Object... fields) {
        b.append(tag);
        for (Object f : fields) b.append('\t').append(esc(String.valueOf(f)));
        b.append('\n');
    }

    /**
     * Escapes the two characters the format itself uses, and the escape.
     *
     * <p>A Flix symbol or a note can contain a tab; one written through unescaped would shift
     * every field after it, and the entry would read back as a different report rather than as a
     * broken one.
     */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String un(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char next = s.charAt(++i);
            out.append(switch (next) {
                case 't' -> '\t';
                case 'n' -> '\n';
                default -> next;
            });
        }
        return out.toString();
    }

    private static int i(String s) {
        return Integer.parseInt(s);
    }

    private static boolean b(String s) {
        return Boolean.parseBoolean(s);
    }
}
