package dev.flixw.metrics;

import dev.flixw.metrics.flix075.Flix075Adapter;
import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.Model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Exercises semantic measurement against the pinned real compiler. */
public final class Flix075AdapterTest {
    private Flix075AdapterTest() { }

    public static void main(String[] args) throws Exception {
        Path project = copyFixture(Path.of(args[0]));
        try {
            Model model = new Flix075Adapter().measure(project);
            require(model.defs().size() == 6, "fixture definitions are measured");
            require(model.lines().code() == 21 && model.lines().docComment() == 4,
                "the real lexer classifies fixture lines");
            DefInfo select = definition(model, "Alpha.selectValue");
            require(select.localDefs() == 1 && select.maxLocalParameters() == 2,
                "local definitions cross the compiler adapter boundary");
            require(select.maxLocalParametersOwner().equals("Alpha.selectValue.helper")
                    && select.maxLocalParametersLine() == 20,
                "the widest local's symbol and declaration line cross the adapter boundary");
            require(select.cognitive() == 2 && select.codeLines() == 4,
                "branches, booleans, and definition code lines are measured");
            DefInfo documented = definition(model, "Alpha.documented");
            require(documented.returnWidth() == 2
                    && documented.flixdocResultCharacters() == 14,
                "typed return shape and its compiler-rendered FlixDoc width are measured");
            require(documented.flixdocParameterCharacters() == 20
                    && documented.formalParameterNames().equals(java.util.List.of("x", "y"))
                    && documented.docText().contains("given argument x"),
                "FlixDoc's rendered formal span and raw documentation cross the adapter boundary");
            require(definition(model, "Alpha.nestedRecord").returnWidth() == 2,
                "return width counts top-level record fields, not their nested fields");
            DefInfo datalog = definition(model, "Alpha.datalog");
            require(datalog.datalogRules() == 3 && datalog.datalogFacts() == 1,
                "Datalog rules and facts cross the compiler adapter boundary separately");
            require(datalog.datalogDependencies().equals(
                        java.util.List.of("Edge", "Path", "Reach"))
                    && datalog.datalogDependencyBreadth() == 3,
                "distinct Datalog body predicates cross the adapter boundary in stable order");
            require(datalog.datalogDependencyDepth() == 2
                    && datalog.recursiveDatalogPredicates().equals(
                        java.util.List.of("Path", "Reach"))
                    && datalog.recursiveDatalogPredicateCount() == 2,
                "dependency depth collapses recursion while retaining recursive predicate names");
            require(definition(model, "testSelectValue").isTest(),
                "compiler test annotations are measured");
            var beta = model.modules().stream().filter(m -> m.name().equals("Beta")).findFirst()
                .orElseThrow();
            require(beta.fanOut() == 1, "resolved direct definition calls form module edges");
            require(beta.dependencies().equals(java.util.List.of("Alpha"))
                    && beta.dependents().isEmpty(),
                "resolved module edge names cross the adapter boundary");

            Model prelude = new Flix075Adapter().measureCompilerSource(project, "Prelude.flix");
            require(!prelude.defs().isEmpty(), "an exact compiler source can be calibrated");
            require(prelude.defs().stream().allMatch(d -> d.file().endsWith("Prelude.flix")),
                "compiler-source calibration does not leak other library definitions");
            require(prelude.effects() == 3 && prelude.enums() == 6 && prelude.typeAliases() == 2,
                "Prelude declarations cross the compiler adapter boundary");

            Files.writeString(project.resolve("src/PolymorphicEffects.flix"), """
                eff Inner[a] {
                    def touch(x: a): Unit
                }

                eff Outer[e: Eff] {
                    def perform(f: Unit -> Unit \\ e): Unit
                }

                def polymorphicEffect(): Unit \\ Outer[Inner[Int32]] =
                    Outer.perform(_ -> Inner.touch(1))
                """);
            Model polymorphic = new Flix075Adapter().measure(project);
            require(definition(polymorphic, "polymorphicEffect").effects()
                    .equals(java.util.List.of("Outer")),
                "a polymorphic effect is atomic and does not expose effects in its arguments");
            System.out.println("Flix075AdapterTest: ok");
        } finally {
            delete(project);
        }
    }

    private static DefInfo definition(Model model, String name) {
        return model.defs().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    static Path copyFixture(Path fixture) throws Exception {
        Path target = Files.createTempDirectory("flixw-metrics-fixture-");
        try (var paths = Files.walk(fixture)) {
            for (Path source : paths.toList()) {
                Path destination = target.resolve(fixture.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(destination);
                else Files.copy(source, destination);
            }
        }
        return target;
    }

    static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
