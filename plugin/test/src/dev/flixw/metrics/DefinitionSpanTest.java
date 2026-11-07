package dev.flixw.metrics;

import dev.flixw.metrics.adapter.Flix0761Adapter;
import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.EffectInfo;
import dev.flixw.metrics.sdk.CompilerModel.Model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A declaration's span starts at its doc comment, whichever Flix release measured it.
 *
 * <p>Flix 0.79.0 moved {@code TypedAst.Def.loc} from the doc comment to the {@code def} line (or
 * its annotation); 0.76.1 through 0.78.0 start it at the doc comment. The expectations below are
 * the 0.77.0 spans, so the same assertions hold on every release the adapter supports. The
 * compiler on the class path is the one measured: run this class once per release.
 */
public final class DefinitionSpanTest {
    private DefinitionSpanTest() { }

    private record Expected(String name, int line, int lines, boolean tooLong, int trailing) { }

    /** A hand-counted definition of {@link #ADJACENT}: line, spanned lines, lines with code. */
    private record Fixed(String name, int line, int lines, int code) { }

    /**
     * Comments next to code that follows them on the same line. Flix 0.76.1 through 0.78.0 end a
     * definition at the comments directly after its last token, and only there: a later token on
     * the same line, a comment before the next definition on its line, and a {@code //} inside a
     * string literal are all told apart by position.
     */
    private static final String ADJACENT = """
        mod Adj {
            pub def strSlash(): String =
                "a // b /* c */"
            // trailing after string

            pub def adjacent(): Int32 = 5
            /* same */ pub def sameLine(): Int32 = 6
            // after sameLine

        }
        mod Inner { pub def inner(): Int32 = 7 }
        // after the module brace
        // two
        """;

    private static final List<Fixed> ADJACENT_DEFS = List.of(
        new Fixed("Adj.strSlash", 2, 3, 2),
        new Fixed("Adj.adjacent", 6, 2, 2),
        new Fixed("Adj.sameLine", 7, 2, 1),
        new Fixed("Inner.inner", 11, 1, 1));

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("write")) {
            // For scripts/test-compiler-compatibility.sh: the same project, written for the
            // compiler on the class path, to be measured through the packaged plugin.
            writeFixture(Path.of(args[1]), new ArrayList<>(), new ArrayList<>());
            return;
        }
        Path project = Files.createTempDirectory("flixw-metrics-span-");
        try {
            List<String> source = new ArrayList<>();
            List<Expected> expected = new ArrayList<>();
            writeFixture(project, source, expected);

            Model model = new Flix0761Adapter().measure(project);
            Map<String, DefInfo> defs = new TreeMap<>();
            for (DefInfo d : model.defs()) defs.put(d.name(), d);
            require(defs.size() == expected.size() + ADJACENT_DEFS.size(),
                "every fixture definition is measured: " + defs.keySet());

            int total = 0;
            for (Expected e : expected) {
                DefInfo d = defs.get("Span." + e.name());
                require(d != null, e.name() + " is measured");
                require(d.line() == e.line(),
                    e.name() + " starts at line " + e.line() + " but reports " + d.line());
                require(d.lines() == e.lines(),
                    e.name() + " spans " + e.lines() + " lines but reports " + d.lines());
                require(d.maxLocalParametersLine() == e.line(),
                    e.name() + " has no local definition, so it reports its own start: "
                        + d.maxLocalParametersLine());
                require(d.codeLines() == e.lines() - e.trailing()
                        - docLinesOf(e.name(), source, e.line()),
                    e.name() + " code lines exclude its comments and blanks: " + d.codeLines());
                total += d.lines();
            }

            for (Fixed f : ADJACENT_DEFS) {
                DefInfo d = defs.get(f.name());
                require(d != null, f.name() + " is measured");
                require(d.line() == f.line() && d.lines() == f.lines() && d.codeLines() == f.code(),
                    f.name() + " is " + f.line() + "+" + f.lines() + " lines with " + f.code()
                        + " code lines but reports " + d.line() + "+" + d.lines() + " with "
                        + d.codeLines());
            }
            require(module(model, "Adj").lines() == 7 && module(model, "Inner").lines() == 1,
                "a comment after a closing brace or a later token on the line is not module code");

            var module = model.modules().stream().filter(m -> m.name().equals("Span"))
                .findFirst().orElseThrow();
            require(module.lines() == total,
                "module lines are the sum of its definitions' spans: " + module.lines()
                    + " instead of " + total);

            List<SourceMetrics.Smell> long_ = Thresholds.apply(model.defs(), model.modules())
                .stream().filter(s -> s.rule().equals("definition-too-long")).toList();
            List<String> flagged = long_.stream().map(SourceMetrics.Smell::subject).sorted()
                .toList();
            List<String> wanted = expected.stream().filter(Expected::tooLong)
                .map(e -> "Span." + e.name()).sorted().toList();
            require(flagged.equals(wanted),
                "definition-too-long names exactly the definitions over 60 lines: " + flagged);
            for (SourceMetrics.Smell smell : long_) {
                Expected e = expected.stream()
                    .filter(x -> ("Span." + x.name()).equals(smell.subject())).findFirst()
                    .orElseThrow();
                require(smell.line() == e.line() && smell.actual() == 61,
                    smell.subject() + " is reported at its doc comment with 61 lines: "
                        + smell.line() + ", " + smell.actual());
            }

            List<Rankings.Rank> longest = Rankings.of(model.defs(), model.modules()).stream()
                .filter(r -> r.measure().equals("longest")).toList();
            require(!longest.isEmpty() && longest.get(0).actual() == 61
                    && longest.stream().allMatch(r ->
                        r.line() == defs.get(r.subject()).line()),
                "the longest ranking is positioned at each definition's span start");

            require(model.effects() == 2 && model.effectDeclarations().size() == 2,
                "both fixture effects are measured");
            EffectInfo documented = effect(model, "Documented");
            require(documented.file().equals("src/Effects.flix") && documented.line() == 1,
                "a documented effect starts at its doc comment: " + documented.line());
            require(effect(model, "Bare").line() == 7,
                "an undocumented effect starts at its declaration: "
                    + effect(model, "Bare").line());
            System.out.println("DefinitionSpanTest: ok");
        } finally {
            Flix0753AdapterTest.delete(project);
        }
    }

    /** Writes the fixture project; {@code source} and {@code expected} describe Span.flix. */
    private static void writeFixture(Path project, List<String> source, List<Expected> expected)
            throws Exception {
        Files.writeString(project.resolve("flix.toml"), Flix0753AdapterTest.adaptManifest("""
            [package]
            name = "span-fixture"
            description = "Definition span fixture."
            version = "0.1.0"
            flix = "0.76.0"
            authors = ["Test <test@example.com>"]
            """));
        Files.createDirectories(project.resolve("src"));

        // Lines are counted as the file is written, so each expectation names the line the
        // doc comment (or, without one, the def) really sits on.
        source.add("mod Span {");
        define(source, expected, "boundary", List.of("/// A documented definition."), 61);
        define(source, expected, "below", List.of("/// One line under the limit."), 60);
        define(source, expected, "noDoc", List.of(), 61);
        define(source, expected, "docTwo", List.of("/// First doc line.", "/// Second."), 61);
        define(source, expected, "docBlank", List.of("/// Doc, then a blank line.", ""), 61);
        define(source, expected, "docComment",
            List.of("/// Doc, then a comment.", "// plain comment"), 61);
        define(source, expected, "docAnnotation",
            List.of("/// Doc, then an annotation.", "@Inline"), 61);
        // Trailing comments: Flix 0.79.0 ends the span at the last token of the definition.
        define(source, expected, "trailingLine", List.of(), 59,
            List.of("// commented out", "// commented out"));
        define(source, expected, "trailingBlock", List.of(), 59,
            List.of("/* block", "   comment */"));
        define(source, expected, "trailingDetached", List.of(), 59, List.of("", "// detached"));
        // A comment that precedes the next definition's doc comment still counts; the doc does not.
        define(source, expected, "trailingBeforeDoc", List.of(), 59,
            List.of("// before a doc comment", "// still trailing"));
        define(source, expected, "documentedAfter", List.of("/// Follows trailing comments."), 61);
        // Followed by the closing brace of the module.
        define(source, expected, "trailingLast", List.of(), 60, List.of("// last"));
        source.add("}");
        Files.writeString(project.resolve("src/Span.flix"),
            String.join("\n", source) + "\n");

        Files.writeString(project.resolve("src/Adjacent.flix"), ADJACENT);

        // An effect declaration with a doc comment, after a blank line.
        Files.writeString(project.resolve("src/Effects.flix"), """
            /// A documented effect.
            /// Two doc lines.
            eff Documented {
                def tick(): Unit
            }

            eff Bare {
                def tock(): Unit
            }
            """);
    }

    /**
     * Appends one definition of exactly {@code total} lines: the preamble, a header, a chain of
     * {@code let} bindings, and a result. Every binding is used, so nothing is an unused variable.
     */
    private static void define(List<String> source, List<Expected> expected, String name,
                               List<String> preamble, int total) {
        define(source, expected, name, preamble, total, List.of());
    }

    /**
     * The same, followed by comment lines that Flix 0.76.1 through 0.78.0 count in the span: every
     * non-doc comment up to the next token, blank lines between them included. The last line must
     * be a comment.
     */
    private static void define(List<String> source, List<Expected> expected, String name,
                               List<String> preamble, int total, List<String> trailing) {
        int first = source.size() + 1;
        for (String line : preamble) source.add(line.isEmpty() ? "" : "    " + line);
        source.add("    pub def " + name + "(): Int32 =");
        int bindings = total - preamble.size() - 2;
        for (int i = 1; i <= bindings; i++)
            source.add("        let v" + i + " = " + (i == 1 ? "1" : "v" + (i - 1) + " + 1") + ";");
        source.add("        v" + bindings);
        require(source.size() - first + 1 == total, name + " is built with the intended length");
        require(trailing.isEmpty() || !trailing.get(trailing.size() - 1).isEmpty(),
            name + " ends its trailing lines with a comment");
        for (String line : trailing) source.add(line.isEmpty() ? "" : "    " + line);
        expected.add(new Expected(name, first, total + trailing.size(),
            total + trailing.size() > 60, trailing.size()));
    }

    /** Doc, comment and blank lines before the def header; an annotation is code. */
    private static int docLinesOf(String name, List<String> source, int first) {
        int header = first;
        int notCode = 0;
        for (; !source.get(header - 1).contains("def " + name + "("); header++)
            if (!source.get(header - 1).trim().startsWith("@")) notCode++;
        return notCode;
    }

    private static dev.flixw.metrics.sdk.CompilerModel.ModuleInfo module(Model model, String name) {
        return model.modules().stream().filter(m -> m.name().equals(name)).findFirst()
            .orElseThrow();
    }

    private static EffectInfo effect(Model model, String name) {
        return model.effectDeclarations().stream().filter(e -> e.name().equals(name))
            .findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
