package dev.flixw.metrics;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import dev.flixw.metrics.sdk.Adapters;
import dev.flixw.metrics.sdk.CompilerModel;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Entry point for the flixw compiler-metrics plugin.
 *
 * <p>Two phases in two JVMs, and the split is forced rather than chosen. The compiler resolves
 * the Java classes its own standard library imports through the application class path, so
 * {@code flix.jar} has to be flat on {@code -cp} -- see {@code CompilerModel.measure(Path)}. This
 * process cannot put it there after the fact, so it re-launches itself with {@code --bridge}.
 *
 * <p>The 270ms that costs is why the cache is consulted <em>here</em>, in the outer phase: a
 * hit answers without the second JVM and without the compiler, in about a tenth of a second
 * against roughly four seconds.
 */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (args.length > 0 && "--bridge".equals(args[0])) {
            bridge(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        String help = help(args);
        if (help != null) {
            System.out.println(help);
            return;
        }
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out.println("metrics " + version());
            return;
        }
        long startedAt = System.nanoTime();
        try {
            Context context = Context.read();
            // The gate inspects without initialising, so an isolated loader is right *here* --
            // nothing is executed through it. It is deliberately not the loader the engine
            // measures with, which cannot be isolated at all.
            try (URLClassLoader inspector = inspectionLoader(context.compilerJar())) {
                CompilerCapabilities capabilities = CompilerCapabilities.inspect(inspector,
                    context.compilerJar());
                if (args.length == 1 && "capabilities".equals(args[0])) {
                    System.out.println(capabilities.json(context));
                    return;
                }
                if (!capabilities.hasEngineApi())
                    throw new Usage("the pinned compiler has no supported engine API\n"
                        + "       jar: " + context.compilerJar() + "\n"
                        + "       missing: " + String.join(", ", capabilities.missing()));
            }
            Options options = parseOptions(args);
            if (options.init()) Initializer.preflight(context.projectRoot(), options.allowDirty());
            MetricsConfig config = MetricsConfig.read(context.projectRoot());
            StableInputs.Result<Optional<Metrics.Report>> cached = StableInputs.run(context,
                version(), ResultCache::key, (sources, digest) -> {
                    CompilerModel.Model hit = cached(context, digest);
                    if (hit == null) return Optional.empty();
                    return Optional.of(Metrics.of(sources.size(), hit,
                        SourceMetrics.measure(context.projectRoot(), sources, config), config));
                });
            if (cached.value().isPresent()) {
                Metrics.Report report = cached.value().orElseThrow();
                Baseline.Comparison comparison = emit(context, report, config, options,
                    cached.digest());
                emitDiagnostics(options, cacheState(context, true), cached.retries(), startedAt);
                if (options.shouldFail(report, comparison)) System.exit(1);
                return;
            }
            System.exit(spawnBridge(context, args));
        } catch (Usage | Metrics.Failure | MetricsConfig.Invalid | Baseline.Invalid
                | CompilerModel.ModelFailure e) {
            System.err.println("metrics: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("metrics: " + e.getMessage());
            System.exit(2);
        }
    }

    /** The second phase: this JVM has the compiler on its class path. */
    private static void bridge(String[] args) {
        long startedAt = System.nanoTime();
        try {
            Context context = Context.read();
            Options options = parseOptions(args);
            if (options.init()) Initializer.preflight(context.projectRoot(), options.allowDirty());
            // Resolved here, in the only JVM that has a compiler on its class path. The
            // adapter is what knows Flix's internals; nothing else in this plugin does.
            CompilerModel model = Adapters.resolve();
            if (model == null)
                throw new Usage("no adapter links against the pinned compiler\n"
                    + "       this build supports: " + String.join(", ", Adapters.known()));
            StableInputs.Result<Measured> stable = StableInputs.run(context, version(),
                ResultCache::key, (sources, digest) -> {
                    CompilerModel.Model measured = model.measure(context.projectRoot());
                    MetricsConfig config = MetricsConfig.read(context.projectRoot());
                    SourceMetrics text = SourceMetrics.measure(context.projectRoot(), sources, config);
                    return new Measured(measured,
                        Metrics.of(sources.size(), measured, text, config), config);
                });
            Measured measured = stable.value();
            // Publish to the cache only after the post-measurement digest matched.
            if (context.pluginCache() != null)
                ResultCache.write(context.pluginCache(), stable.digest(), Wire.encode(measured.model()));
            // Written before rendering, and by this phase rather than the outer one. The outer
            // phase inherits this process's stdout, so it never sees the report as a value --
            // and parsing it back out of a stream the compiler also writes to would be reading
            // our own output past whatever Flix chose to print alongside it.
            Baseline.Comparison comparison = emit(context, measured.report(), measured.config(),
                options, stable.digest());
            emitDiagnostics(options, cacheState(context, false), stable.retries(), startedAt);
            if (options.shouldFail(measured.report(), comparison)) System.exit(1);
        } catch (LinkageError e) {
            // The promise is a sentence, never a stack trace. The descriptor gate covers the
            // adapter's direct Flix linkage; this remains the final guard for transitive linkage
            // failures while the JVM loads compiler implementation code.
            System.err.println("metrics: this compiler is not the one this build supports");
            System.err.println("       " + e);
            System.err.println("       run: ./flixw metrics capabilities");
            System.exit(2);
        } catch (Usage | Metrics.Failure | MetricsConfig.Invalid | Baseline.Invalid
                | CompilerModel.ModelFailure e) {
            System.err.println("metrics: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("metrics: " + e.getMessage());
            System.exit(2);
        }
    }

    private record Measured(CompilerModel.Model model, Metrics.Report report, MetricsConfig config) { }

    private static Baseline.Comparison emit(Context context, Metrics.Report report,
                                             MetricsConfig config, Options options,
                                             String inputDigest) throws IOException {
        Provenance provenance = Provenance.of(context, version(), inputDigest);
        if (options.init()) {
            System.out.print(Initializer.write(context.projectRoot(),
                report.render(Metrics.Format.JSON, provenance, config)));
            return null;
        }
        Baseline.Comparison comparison = options.compare(context.projectRoot(), report, config);
        writeOutput(context.projectRoot(), options.output(),
            report.render(options.format(), provenance, config, comparison, options.view(),
                options.filter()));
        return comparison;
    }

    /** Writes a report beside a temporary file, then publishes it with one atomic rename. */
    static void writeOutput(Path projectRoot, Path requested, String contents) throws IOException {
        if (requested == null) {
            System.out.print(contents);
            return;
        }
        Path target = (requested.isAbsolute() ? requested : projectRoot.resolve(requested))
            .toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("output directory does not exist: " + parent);
        Path temporary = Files.createTempFile(parent, ".flixw-metrics-", ".tmp");
        try {
            Files.writeString(temporary, contents);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("output filesystem does not support atomic replacement: "
                    + target, e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String cacheState(Context context, boolean hit) {
        return context.pluginCache() == null ? "disabled" : hit ? "hit" : "miss";
    }

    private static void emitDiagnostics(Options options, String cache, int retries,
                                        long startedAt) {
        if (!options.diagnostics()) return;
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        System.err.println(diagnostic(cache, retries, elapsedMillis));
    }

    static String diagnostic(String cache, int retries, long elapsedMillis) {
        return "metrics diagnostics: cache=" + cache + " elapsedMs=" + elapsedMillis
            + " retries=" + retries;
    }

    /**
     * The compiler's measurements for these exact inputs, or null.
     *
     * <p>The <em>measurements</em> are cached, never the report: they are facts about the source
     * and cannot go stale while the key holds. Findings and rankings are judgements about those
     * facts, cost microseconds, and are recomputed every run -- so editing a threshold changes
     * the next run rather than waiting for a cache miss.
     *
     * <p>Every failure returns null, which costs a recomputation and never a wrong answer. That
     * asymmetry is the whole design rule here: this cache may only make the plugin faster, never
     * make it disagree with the compiler.
     */
    private static CompilerModel.Model cached(Context context, String inputDigest) {
        if (context.pluginCache() == null) return null;
        String text = ResultCache.read(context.pluginCache(), inputDigest);
        return text == null ? null : Wire.decode(text);
    }

    /**
     * Inspection only, and isolated because it can afford to be.
     *
     * <p>{@link Class#forName(String, boolean, ClassLoader)} with initialisation off never runs
     * compiler code, so nothing here depends on the class path the compiler would want at
     * runtime. Parenting to the platform loader keeps the compiler's bundled ASM, JLine, gson
     * and json4s out of this process's own resolution while the gate asks its questions.
     */
    private static URLClassLoader inspectionLoader(Path compilerJar) {
        try {
            return new URLClassLoader("flix-inspect", new URL[] {compilerJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        } catch (MalformedURLException e) {
            throw new Usage("cannot open the compiler jar " + compilerJar + ": " + e.getMessage());
        }
    }

    private static int spawnBridge(Context context, String[] args) {
        try {
            return new ProcessBuilder(bridgeCommand(context, args))
                .directory(context.projectRoot().toFile())
                .inheritIO().start().waitFor();
        } catch (IOException e) {
            throw new Usage("cannot start compiler bridge: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Usage("interrupted while waiting for compiler bridge");
        }
    }

    static List<String> bridgeCommand(Context context, String[] args) {
        try {
            Path plugin = Path.of(Main.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
            List<String> command = new ArrayList<>();
            command.add(context.java().toString());
            // Flix's constraint visitors are deliberately recursive. Its own compiler constants
            // specify this worker-stack floor; making the bridge explicit avoids platform JVM
            // defaults (notably hosted Linux) deciding whether a valid large project compiles.
            command.add("-Xss64m");
            command.add("-cp");
            command.add(plugin + File.pathSeparator + context.compilerJar());
            command.add(Main.class.getName());
            command.add("--bridge");
            command.addAll(Arrays.asList(args));
            return List.copyOf(command);
        } catch (URISyntaxException e) {
            throw new Usage("cannot start compiler bridge: " + e.getMessage());
        }
    }

    private static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }

    record Options(Metrics.Format format, String failOn, Path baseline, String failOnNew,
                   Path output, Metrics.View view, boolean init, boolean allowDirty,
                   boolean diagnostics, PresentationFilter filter) {
        boolean shouldFail(Metrics.Report report, Baseline.Comparison comparison) {
            boolean existing = false;
            if (failOn != null) {
                int threshold = RuleDefinitions.levelRank(failOn);
                existing = report.smells().stream().anyMatch(smell -> RuleDefinitions.levelRank(
                    RuleDefinitions.byId(smell.rule()).level()) >= threshold);
            }
            return existing || failOnNew != null && comparison != null
                && comparison.crosses(failOnNew);
        }

        Baseline.Comparison compare(Path projectRoot, Metrics.Report report, MetricsConfig config)
                throws IOException {
            if (baseline == null) return null;
            Path resolved = baseline.isAbsolute() ? baseline : projectRoot.resolve(baseline);
            return Baseline.compare(resolved.normalize(), report, config);
        }

    }

    static Options parseOptions(String[] args) {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        if (!rest.isEmpty() && "init".equals(rest.get(0))) {
            rest.remove(0);
            boolean allowDirty = takeFlag(rest, "--allow-dirty");
            boolean diagnostics = takeFlag(rest, "--diagnostics");
            if (!rest.isEmpty()) throw new Usage("unknown init option " + rest.get(0));
            return new Options(Metrics.Format.JSON, null, null, null, null, Metrics.View.FULL, true,
                allowDirty, diagnostics, PresentationFilter.none());
        }
        if (!rest.isEmpty() && "report".equals(rest.get(0))) rest.remove(0);
        boolean diagnostics = takeFlag(rest, "--diagnostics");
        Metrics.Format format = Metrics.Format.TEXT;
        String failOn = null;
        Path baseline = null;
        String failOnNew = null;
        Path output = null;
        Metrics.View view = Metrics.View.FULL;
        String rule = null;
        String severity = null;
        String file = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rest.size(); i += 2) {
            String option = rest.get(i);
            if (!option.startsWith("--"))
                throw new Usage("unknown command or option " + option);
            if (!List.of("--format", "--fail-on", "--baseline", "--fail-on-new", "--output",
                    "--view", "--rule", "--severity", "--file")
                    .contains(option))
                throw new Usage("unknown option " + option);
            if (i + 1 >= rest.size()) throw new Usage(option + " requires a value");
            if (!seen.add(option)) throw new Usage("repeated option " + option);
            switch (option) {
                case "--format" -> format = Metrics.Format.parse(rest.get(i + 1));
                case "--fail-on" -> {
                    String level = rest.get(i + 1);
                    if (!List.of("note", "warning", "error").contains(level))
                        throw new Usage("unknown failure level " + level
                            + " (expected note, warning or error)");
                    failOn = level;
                }
                case "--baseline" -> baseline = Path.of(rest.get(i + 1));
                case "--output" -> output = Path.of(rest.get(i + 1));
                case "--view" -> view = Metrics.View.parse(rest.get(i + 1));
                case "--rule" -> rule = rest.get(i + 1);
                case "--severity" -> severity = rest.get(i + 1);
                case "--file" -> file = rest.get(i + 1);
                case "--fail-on-new" -> {
                    String level = rest.get(i + 1);
                    if (!List.of("note", "warning", "error").contains(level))
                        throw new Usage("unknown failure level " + level
                            + " (expected note, warning or error)");
                    failOnNew = level;
                }
                default -> throw new AssertionError("validated option " + option);
            }
        }
        if (failOnNew != null && baseline == null)
            throw new Usage("--fail-on-new requires --baseline");
        if (view != Metrics.View.FULL && format != Metrics.Format.JSON)
            throw new Usage("--view requires --format json");
        if (view == Metrics.View.CHANGES && baseline == null)
            throw new Usage("--view changes requires --baseline");
        PresentationFilter filter = PresentationFilter.of(rule, severity, file);
        if (view == Metrics.View.SUMMARY && filter.active())
            throw new Usage("finding filters cannot be used with --view summary");
        return new Options(format, failOn, baseline, failOnNew, output, view, false, false,
            diagnostics, filter);
    }

    private static boolean takeFlag(List<String> args, String flag) {
        int first = args.indexOf(flag);
        if (first < 0) return false;
        if (args.lastIndexOf(flag) != first) throw new Usage("repeated option " + flag);
        args.remove(first);
        return true;
    }

    static String help(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) return usage();
        if (args.length != 2 || !"--help".equals(args[1])) return null;
        return switch (args[0]) {
            case "report" -> "usage: ./flixw metrics report"
                + " [--format text|json|md|sarif]"
                + " [--fail-on note|warning|error] [--baseline report.json]"
                + " [--fail-on-new note|warning|error] [--view full|summary|findings|changes]"
                + " [--rule id] [--severity note|warning|error] [--file glob]"
                + " [--output path] [--diagnostics]\n\n"
                + "Measures the project and writes a report to stdout or atomically to --output.\n"
                + "Finding filters affect presentation only; --severity means at least that level."
                + " --file uses portable * and ** globs, and never excludes a module-level"
                + " finding (e.g. wide-coupling), which has no file of its own to match against.";
            case "init" -> "usage: ./flixw metrics init [--allow-dirty] [--diagnostics]\n\n"
                + "Creates a starter policy and clean-tree metrics baseline without overwriting"
                + " files.";
            case "capabilities" -> "usage: ./flixw metrics capabilities\n\n"
                + "Reports compiler compatibility and the adapter ABI gate as JSON.";
            default -> null;
        };
    }

    private static String usage() {
        return "usage: ./flixw metrics [report] [--format text|json|md|sarif]"
            + " [--fail-on note|warning|error] [--baseline report.json]"
            + " [--fail-on-new note|warning|error] [--view full|summary|findings|changes]"
            + " [--rule id] [--severity note|warning|error] [--file glob]"
            + " [--output path] [--diagnostics]\n"
            + "       ./flixw metrics init [--allow-dirty] [--diagnostics]\n"
            + "       ./flixw metrics capabilities\n\n"
            + "Reads typed compiler data through a supported compiler adapter.\n"
            + "Results are cached under FLIXW_PLUGIN_CACHE and reused until the sources, the\n"
            + "manifest, the pinned compiler or this plugin change.";
    }

    record Context(Path projectRoot, Path compilerJar, Path java, Path pluginCache) {
        static Context read() {
            if (!"1".equals(System.getenv("FLIXW_ABI_VERSION")))
                throw new Usage("requires flixw ABI version 1");
            // Optional, unlike the rest. It arrived after ABI 1 was first published, so a
            // wrapper that predates it simply does not set it -- and a report is correct
            // without a cache, so absence disables caching rather than failing the run.
            String cache = System.getenv("FLIXW_PLUGIN_CACHE");
            return new Context(path("FLIXW_PROJECT_ROOT"), path("FLIXW_COMPILER_JAR"),
                path("FLIXW_JAVA_HOME").resolve("bin").resolve("java"),
                cache == null || cache.isBlank()
                    ? null : Path.of(cache).toAbsolutePath().normalize());
        }

        private static Path path(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new Usage("missing " + name);
            return Path.of(value).toAbsolutePath().normalize();
        }
    }

    static final class Usage extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Usage(String message) { super(message); }
    }
}
