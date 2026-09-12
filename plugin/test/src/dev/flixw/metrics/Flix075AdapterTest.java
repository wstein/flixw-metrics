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
            require(model.defs().size() == 5, "fixture definitions are measured");
            require(model.lines().code() == 15 && model.lines().docComment() == 1,
                "the real lexer classifies fixture lines");
            DefInfo select = definition(model, "Alpha.selectValue");
            require(select.localDefs() == 1 && select.maxLocalParameters() == 2,
                "local definitions cross the compiler adapter boundary");
            require(select.cognitive() == 2 && select.codeLines() == 4,
                "branches, booleans, and definition code lines are measured");
            require(definition(model, "Alpha.documented").returnWidth() == 2,
                "typed return shape is measured");
            require(definition(model, "Alpha.nestedRecord").returnWidth() == 2,
                "return width counts top-level record fields, not their nested fields");
            require(definition(model, "testSelectValue").isTest(),
                "compiler test annotations are measured");
            var beta = model.modules().stream().filter(m -> m.name().equals("Beta")).findFirst()
                .orElseThrow();
            require(beta.fanOut() == 1, "resolved direct definition calls form module edges");

            Model prelude = new Flix075Adapter().measureCompilerSource(project, "Prelude.flix");
            require(!prelude.defs().isEmpty(), "an exact compiler source can be calibrated");
            require(prelude.defs().stream().allMatch(d -> d.file().endsWith("Prelude.flix")),
                "compiler-source calibration does not leak other library definitions");
            require(prelude.effects() == 3 && prelude.enums() == 6 && prelude.typeAliases() == 2,
                "Prelude declarations cross the compiler adapter boundary");
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
