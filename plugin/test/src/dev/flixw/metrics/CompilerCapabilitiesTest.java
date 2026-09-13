package dev.flixw.metrics;

import dev.flixw.metrics.sdk.AdapterAbi;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.spi.ToolProvider;

/** Checks that capability detection is based on loadable compiler classes, not filenames. */
public final class CompilerCapabilitiesTest {
    private CompilerCapabilitiesTest() { }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("flixw-metrics-test-");
        try {
            Path empty = work.resolve("empty.jar");
            jar(empty, work.resolve("none"));
            CompilerCapabilities noApi = inspect(empty);
            require(!noApi.hasFlixApi() && !noApi.hasEngineApi() && !noApi.hasNativeMetrics(),
                "empty jar has no capabilities");
            require(!noApi.missing().isEmpty(), "an empty jar reports what it is missing");
            Set<String> linked = AdapterAbi.contracts().stream()
                .flatMap(contract -> contract.references().stream())
                .map(AdapterAbi.Reference::display).collect(Collectors.toSet());
            require(AdapterAbi.contracts().stream().anyMatch(contract -> noApi.missing().containsAll(
                contract.references().stream().map(AdapterAbi.Reference::display).toList())),
                "the capability gate reports every reference in its closest adapter contract");
            require(AdapterAbi.contracts().size() == 5,
                "the capability gate derives a separate contract for each adapter");
            require(linked.stream().anyMatch(r -> r.contains("formatType$default$2:()")),
                "the bytecode contract includes Scala default-argument accessors");
            require(linked.stream().anyMatch(r -> r.contains("MODULE$:") && r.contains("FormatType$")),
                "the bytecode contract includes Scala singleton fields");
            require(linked.stream().anyMatch(r -> r.contains("formatType:(Lca/uwaterloo/flix/language/ast/Type;")),
                "the bytecode contract includes full JVM method descriptors");

            Path source = work.resolve("source");
            write(source, "ca/uwaterloo/flix/api/Flix.java",
                "package ca.uwaterloo.flix.api; public final class Flix { }");
            write(source, "ca/uwaterloo/flix/tools/Metrics$.java",
                "package ca.uwaterloo.flix.tools; public final class Metrics$ { }");
            write(source, "ca/uwaterloo/flix/language/phase/Lexer$.java",
                "package ca.uwaterloo.flix.language.phase; public final class Lexer$ { }");
            write(source, "ca/uwaterloo/flix/language/ast/TokenKind.java",
                "package ca.uwaterloo.flix.language.ast; public interface TokenKind { }");
            write(source, "ca/uwaterloo/flix/language/fmt/FormatType$.java",
                "package ca.uwaterloo.flix.language.fmt; public final class FormatType$ { "
                + "public Object formatType(Object a, Object b, Object c, Object d, Object e) "
                + "{ return null; } }");
            Path classes = work.resolve("classes");
            Files.createDirectories(classes);
            int compiled = ToolProvider.findFirst("javac").orElseThrow()
                .run(System.out, System.err, "-d", classes.toString(),
                    source.resolve("ca/uwaterloo/flix/api/Flix.java").toString(),
                    source.resolve("ca/uwaterloo/flix/tools/Metrics$.java").toString(),
                    source.resolve("ca/uwaterloo/flix/language/phase/Lexer$.java").toString(),
                    source.resolve("ca/uwaterloo/flix/language/ast/TokenKind.java").toString(),
                    source.resolve("ca/uwaterloo/flix/language/fmt/FormatType$.java").toString());
            require(compiled == 0, "fixture compiler succeeds");
            Path metrics = work.resolve("metrics.jar");
            jar(metrics, classes);
            CompilerCapabilities found = inspect(metrics);
            require(found.hasFlixApi() && !found.hasEngineApi() && found.hasNativeMetrics(),
                "fixture distinguishes Flix presence from the adapter engine API");
            require(found.missing().contains("class dev.flix.runtime.Global"),
                "the gate requires the compiler runtime class used by its standard library");

            // The gate must name the members the engine truly calls. A jar carrying a Flix
            // class with the *old* gate's members and none of the engine's must still fail:
            // that combination is exactly what used to pass and then die mid-run.
            require(found.missing().stream().anyMatch(m -> m.contains("Bootstrap")),
                "the gate requires Bootstrap, which the engine calls");
            require(found.missing().stream().anyMatch(m -> m.contains("Options$")),
                "the gate requires Options$, which the engine calls");
            require(found.missing().stream().noneMatch(m -> m.contains("addFile")),
                "the gate does not require addFile, which the engine never calls");
            require(found.missing().stream().anyMatch(m -> m.contains("Lexer$.lex:(")),
                "the gate requires the lexer entry point used for source metrics");
            require(found.missing().stream().anyMatch(m -> m.contains("TokenKind.isComment:()Z")),
                "the gate requires token comment classification");
            String formatter = linked.stream()
                .filter(r -> r.contains("FormatType$.formatType:("))
                .findFirst().orElseThrow();
            require(found.missing().contains(formatter),
                "a same-arity formatter with the wrong JVM descriptor fails the gate");
            require(found.missing().stream().anyMatch(m -> m.contains("TypedAst$Constraint")),
                "the gate requires typed Datalog nodes");
            require(found.missing().stream().anyMatch(m -> m.contains("TypedAst$CatchRule")),
                "the gate requires every control-flow rule class the adapter matches");
            require(found.missing().stream().anyMatch(m -> m.contains("TypeConstructor")),
                "the gate requires typed return-shape constructors");
            require(found.missing().stream().anyMatch(m -> m.contains("Expr$ApplyDef")),
                "the gate requires the call-site node module coupling resolves through");
            require(found.missing().stream().anyMatch(m -> m.contains("FormalParam")),
                "the gate requires the formal parameter node used for names and FlixDoc width");

            if (args.length >= 1) {
                CompilerCapabilities stock = inspect(Path.of(args[0]));
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the pinned compiler satisfies every bytecode-derived adapter requirement");
            }
            if (args.length >= 2) {
                Path older = Path.of(args[1]);
                CompilerCapabilities stock = inspect(older);
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the older compiler satisfies one bytecode-derived adapter requirement");
                require(!missing(older, AdapterAbi.contracts().get(0)).isEmpty(),
                    "the older compiler does not satisfy the newer adapter contract");
                require(missing(older, AdapterAbi.contracts().get(1)).isEmpty(),
                    "the older compiler satisfies its dedicated adapter contract");
            }
            if (args.length >= 3) {
                Path oldest = Path.of(args[2]);
                CompilerCapabilities stock = inspect(oldest);
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the oldest compiler satisfies one bytecode-derived adapter requirement");
                require(!missing(oldest, AdapterAbi.contracts().get(0)).isEmpty()
                        && !missing(oldest, AdapterAbi.contracts().get(1)).isEmpty(),
                    "the oldest compiler does not satisfy either newer adapter contract");
                require(missing(oldest, AdapterAbi.contracts().get(2)).isEmpty(),
                    "the oldest compiler satisfies its dedicated adapter contract");
            }
            if (args.length >= 4) {
                Path earliest = Path.of(args[3]);
                CompilerCapabilities stock = inspect(earliest);
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the earliest compiler satisfies one bytecode-derived adapter requirement");
                require(!missing(earliest, AdapterAbi.contracts().get(2)).isEmpty(),
                    "the earliest compiler does not satisfy the 0.67.2 adapter contract");
                require(missing(earliest, AdapterAbi.contracts().get(3)).isEmpty(),
                    "the earliest compiler satisfies its dedicated adapter contract");
            }
            if (args.length >= 5) {
                Path validation = Path.of(args[4]);
                CompilerCapabilities stock = inspect(validation);
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the Validation-era compiler satisfies an adapter contract");
                require(!missing(validation, AdapterAbi.contracts().get(3)).isEmpty(),
                    "the Validation-era compiler does not satisfy the Result-era contract");
                require(missing(validation, AdapterAbi.contracts().get(4)).isEmpty(),
                    "the Validation-era compiler satisfies its dedicated adapter contract");
            }
        } finally {
            delete(work);
        }
    }

    /** Inspection needs a loader now; the gate never initialises through it. */
    private static CompilerCapabilities inspect(Path jar) throws IOException {
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            return CompilerCapabilities.inspect(loader, jar);
        }
    }

    private static List<String> missing(Path jar, AdapterAbi.Contract contract) throws IOException {
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            return AdapterAbi.missing(contract, loader);
        }
    }

    private static void write(Path base, String relative, String source) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static void jar(Path output, Path contents) throws IOException, InterruptedException {
        Files.createDirectories(contents);
        int status = new ProcessBuilder("jar", "--create", "--file", output.toString(), "-C",
            contents.toString(), ".").inheritIO().start().waitFor();
        require(status == 0, "fixture jar succeeds");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
