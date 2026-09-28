package dev.flixw.metrics;

import dev.flixw.metrics.adapter.Flix0761Adapter;
import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.EffectInfo;
import dev.flixw.metrics.sdk.CompilerModel.Model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Exercises semantic measurement against the pinned real compiler. */
public final class Flix0761AdapterTest {
    private Flix0761AdapterTest() { }

    public static void main(String[] args) throws Exception {
        Path project = copyFixture(Path.of(args[0]));
        try {
            Model model = new Flix0761Adapter().measure(project);
            require(model.defs().size() == 6, "fixture definitions are measured");
            require(model.defs().stream().allMatch(definition ->
                    definition.file().startsWith("src/")
                        || definition.file().startsWith("test/")),
                "only Origin.User definitions enter project metrics");
            Path relativeProject = Path.of("").toAbsolutePath().normalize().relativize(project);
            Model relativeModel = new Flix0761Adapter().measure(relativeProject);
            require(relativeModel.defs().stream().allMatch(definition ->
                    definition.file().startsWith("src/")
                        || definition.file().startsWith("test/")),
                "relative project roots produce project-relative source names");
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
            require(select.handlers() == 0 && select.handledOperations() == 0
                    && select.maxHandlerOperations() == 0 && select.resumptions() == 0,
                "ordinary branches are not counted as effect handlers");
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

            writeRestrictableFixture(project);
            assertRestrictableEnums(new Flix0761Adapter().measure(project));

            // Effect declarations measured before handlers are added: assertEffectMetrics
            // checks the model's total effect count, and HandlerMetrics.flix below declares
            // two effects of its own that would otherwise inflate it.
            writeEffectFixture(project);
            Model effects = new Flix0761Adapter().measure(project);
            assertEffectMetrics(effects);
            Files.writeString(project.resolve("src/GenericEffect.flix"), """
                eff GenericEffect[a, b] {
                    def combine(left: a, right: b): (a, b)
                }
                """);
            Model generic = new Flix0761Adapter().measure(project);
            EffectInfo genericEffect = effect(generic, "GenericEffect");
            require(genericEffect.typeParameters() == 2
                    && genericEffect.operationCount() == 1
                    && genericEffect.maxOperationArity() == 2,
                "generic effect parameters and typed operation arity are measured");
            writeHandlerFixture(project);
            assertHandlerMetrics(new Flix0761Adapter().measure(project));

            Model prelude = new Flix0761Adapter().measureCompilerSource(project, "Prelude.flix");
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

                def forward(f: Unit -> Unit \\ ef): Unit \\ ef = f()

                def polymorphicEffect(): Unit \\ Outer[Inner[Int32]] =
                    Outer.perform(_ -> Inner.touch(1))

                def otherPolymorphicEffect(): Unit \\ Outer[Inner[String]] =
                    Outer.perform(_ -> Inner.touch("value"))

                def genericEffect[a: Type](x: a): Unit \\ Outer[Inner[a]] =
                    Outer.perform(_ -> Inner.touch(x))
                """);
            Model polymorphic = new Flix0761Adapter().measure(project);
            DefInfo firstEffect = definition(polymorphic, "polymorphicEffect");
            DefInfo secondEffect = definition(polymorphic, "otherPolymorphicEffect");
            DefInfo forward = definition(polymorphic, "forward");
            DefInfo genericDeclaredEffect = definition(polymorphic, "genericEffect");
            require(!forward.isPure() && forward.effectPolymorphic()
                    && forward.effects().isEmpty(),
                "a bare effect variable is non-pure without becoming a named capability");
            require(!firstEffect.effectPolymorphic() && !secondEffect.effectPolymorphic()
                    && genericDeclaredEffect.effectPolymorphic(),
                "type variables, including those inside effect arguments, define polymorphism");
            require(firstEffect.effects().equals(java.util.List.of("Outer"))
                    && secondEffect.effects().equals(java.util.List.of("Outer")),
                "a polymorphic effect is atomic and does not expose effects in its arguments");
            require(firstEffect.effectDetails().get(0).name().equals("Outer")
                    && firstEffect.effectDetails().get(0).argumentCount() == 1
                    && firstEffect.effectDetails().get(0).arguments()
                        .equals(java.util.List.of("Inner[Int32]"))
                    && secondEffect.effectDetails().get(0).arguments()
                        .equals(java.util.List.of("Inner[String]")),
                "rendered arguments distinguish equal-arity effect instantiations");

            Files.writeString(project.resolve("src/JavaInterop.flix"), """
                import java.io.File
                import java.io.IOException

                def javaInterop(): Unit \\ IO =
                    try {
                        let file = new File("foo.txt");
                        if ((file instanceof File) and file.exists()) () else ()
                    } catch {
                        case _: IOException => ()
                    }
                """);
            Model javaInterop = new Flix0761Adapter().measure(project);
            DefInfo interop = definition(javaInterop, "javaInterop");
            require(interop.effects().equals(java.util.List.of("IO"))
                    && interop.cognitive() == 3,
                "descriptor-backed Java nodes preserve effects and nested branch measurement");
            assertMountedPackageModules();
            System.out.println("Flix0761AdapterTest: ok");
        } finally {
            delete(project);
        }
    }

    /**
     * A mounted package is named under a root that cannot be written in source. Flix 0.77 shows
     * that root as the package identifier; the module graph must do the same, must keep a
     * package module apart from a project module of the same name, and must not count package
     * definitions as project code.
     *
     * <p>Offline: the package manager trusts a cached release in {@code lib/}, so the package is
     * built here as the exact files a download would have left there.
     */
    static void assertMountedPackageModules() throws Exception {
        Path project = Files.createTempDirectory("flixw-metrics-mounted-");
        try {
            Path release = project.resolve("lib/github/fixture/clerk/1.0.0");
            Files.createDirectories(release);
            String manifest = """
                [package]
                name = "clerk"
                description = "Mounted package fixture."
                version = "1.0.0"
                flix = "0.76.1"
                authors = ["Test <test@example.com>"]
                """;
            Files.writeString(release.resolve("clerk-1.0.0.toml"), manifest);
            try (var zip = new java.util.zip.ZipOutputStream(
                    Files.newOutputStream(release.resolve("clerk-1.0.0.fpkg")))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("flix.toml"));
                zip.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.putNextEntry(new java.util.zip.ZipEntry("src/Board.flix"));
                zip.write("pub mod Board { pub def place(): Int32 = 42 }\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Files.writeString(project.resolve("flix.toml"), """
                [package]
                name = "consumer"
                description = "Consumes a mounted package."
                version = "0.1.0"
                flix = "0.76.1"
                authors = ["Test <test@example.com>"]

                [dependencies]
                "github:fixture/clerk" = { version = "1.0.0", mount = "clerk" }
                """);
            Files.createDirectories(project.resolve("src"));
            Files.writeString(project.resolve("src/Main.flix"), """
                mod Board {
                    pub def size(): Int32 = 1
                }

                mod Game {
                    use clerk::Board.place

                    pub def play(): Int32 = place() + Board.size()
                }
                """);
            Model model = new Flix0761Adapter().measure(project);
            require(model.defs().size() == 2
                    && model.defs().stream().allMatch(d -> d.file().equals("src/Main.flix")),
                "mounted package definitions do not enter project metrics");
            var game = model.modules().stream().filter(m -> m.name().equals("Game"))
                .findFirst().orElseThrow();
            require(game.fanOut() == 2,
                "a package module and a project module of the same name stay distinct");
            require(game.dependencies().equals(
                    java.util.List.of("Board", "github:fixture/clerk.Board")),
                "a mounted package module is named by its package identifier: "
                    + game.dependencies());
        } finally {
            delete(project);
        }
    }

    static void writeHandlerFixture(Path project) throws Exception {
        Files.writeString(project.resolve("src/HandlerMetrics.flix"), """
            eff InnerHandler {
                def inner(): Int32
            }

            eff OuterHandler {
                def first(x: Int32): Int32
                def second(): Int32
            }

            def handlerMetrics(x: Int32): Int32 =
                run {
                    let outer = run {
                        OuterHandler.first(x) + OuterHandler.second()
                    } with handler OuterHandler {
                        def first(value, resume) = resume(value) + resume(value)
                        def second(_resume) = 0
                    };
                    InnerHandler.inner() + outer
                } with handler InnerHandler {
                    def inner(resume) = resume(1)
                }
            """);
    }

    static void assertHandlerMetrics(Model model) {
        DefInfo definition = definition(model, "handlerMetrics");
        require(definition.handlers() == 2
                && definition.handledOperations() == 3
                && definition.maxHandlerOperations() == 2,
            "nested handlers and their operation clauses are measured");
        require(definition.resumptions() == 3,
            "zero and multiple direct continuation invocations are measured");
        require(definition.cognitive() == 3,
            "handler metrics do not change cognitive-complexity semantics: "
                + definition.cognitive());
    }

    private static DefInfo definition(Model model, String name) {
        return model.defs().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    static void writeEffectFixture(Path project) throws Exception {
        Files.writeString(project.resolve("src/EffectMetrics.flix"), """
            eff SmallEffect {
                def ping(): Unit
            }

            eff BroadEffect {
                def reset(): Unit
                def combine(left: Int32, right: Int32): Int32
            }
            """);
    }

    static void writeRestrictableFixture(Path project) throws Exception {
        Files.writeString(project.resolve("src/Restrictable.flix"), """
            restrictable enum Choice[i] {
                case First
                case Second
            }
            """);
    }

    static void assertRestrictableEnums(Model model) {
        require(model.restrictableEnums() == 1,
            "restrictable-enum declarations cross the compiler adapter boundary");
    }

    static void assertEffectMetrics(Model model) {
        require(model.effects() == 2 && model.effectDeclarations().size() == model.effects(),
            "the effect aggregate agrees with its declaration records");
        EffectInfo small = effect(model, "SmallEffect");
        require(small.file().equals("src/EffectMetrics.flix") && small.line() == 1
                && small.typeParameters() == 0 && small.operationCount() == 1
                && small.operations().get(0).name().equals("ping")
                && small.operations().get(0).arity() == 0,
            "a nongeneric effect retains its location and nullary operation");
        EffectInfo broad = effect(model, "BroadEffect");
        require(broad.line() == 5 && broad.operationCount() == 2
                && broad.maxOperationArity() == 2
                && broad.operations().get(0).name().equals("reset")
                && broad.operations().get(1).name().equals("combine")
                && broad.operations().get(1).arity() == 2,
            "effect operations retain deterministic declaration order and typed arity");
    }

    private static EffectInfo effect(Model model, String name) {
        return model.effectDeclarations().stream().filter(e -> e.name().equals(name))
            .findFirst().orElseThrow();
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
