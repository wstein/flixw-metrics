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

    private record Expected(String name, int line, int lines, boolean tooLong) { }

    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("flixw-metrics-span-");
        try {
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
            List<String> source = new ArrayList<>();
            List<Expected> expected = new ArrayList<>();
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
            source.add("}");
            Files.writeString(project.resolve("src/Span.flix"),
                String.join("\n", source) + "\n");

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

            Model model = new Flix0761Adapter().measure(project);
            Map<String, DefInfo> defs = new TreeMap<>();
            for (DefInfo d : model.defs()) defs.put(d.name(), d);
            require(defs.size() == expected.size(),
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
                require(d.codeLines() == e.lines() - docLinesOf(e.name(), source, e.line()),
                    e.name() + " code lines exclude its comments and blanks: " + d.codeLines());
                total += d.lines();
            }

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

    /**
     * Appends one definition of exactly {@code total} lines: the preamble, a header, a chain of
     * {@code let} bindings, and a result. Every binding is used, so nothing is an unused variable.
     */
    private static void define(List<String> source, List<Expected> expected, String name,
                               List<String> preamble, int total) {
        int first = source.size() + 1;
        for (String line : preamble) source.add(line.isEmpty() ? "" : "    " + line);
        source.add("    pub def " + name + "(): Int32 =");
        int bindings = total - preamble.size() - 2;
        for (int i = 1; i <= bindings; i++)
            source.add("        let v" + i + " = " + (i == 1 ? "1" : "v" + (i - 1) + " + 1") + ";");
        source.add("        v" + bindings);
        expected.add(new Expected(name, first, total, total > 60));
        require(source.size() - first + 1 == total, name + " is built with the intended length");
    }

    /** Doc, comment and blank lines before the def header; an annotation is code. */
    private static int docLinesOf(String name, List<String> source, int first) {
        int header = first;
        int notCode = 0;
        for (; !source.get(header - 1).contains("def " + name + "("); header++)
            if (!source.get(header - 1).trim().startsWith("@")) notCode++;
        return notCode;
    }

    private static EffectInfo effect(Model model, String name) {
        return model.effectDeclarations().stream().filter(e -> e.name().equals(name))
            .findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
