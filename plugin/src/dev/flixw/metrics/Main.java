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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the flixw compiler-metrics plugin.
 *
 * <p>Two phases in two JVMs, and the split is forced rather than chosen. The compiler resolves
 * the Java classes its own standard library imports through the application class path, so
 * {@code flix.jar} has to be flat on {@code -cp} -- see {@link ReflectionEngine#measure}. This
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
        if (args.length == 1 && "--help".equals(args[0])) {
            usage();
            return;
        }
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out.println("metrics " + version());
            return;
        }
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
                if (!capabilities.hasReflectionApi())
                    throw new Usage("the pinned compiler has no supported reflection API\n"
                        + "       jar: " + context.compilerJar() + "\n"
                        + "       missing: " + String.join(", ", capabilities.missing()));
            }
            Metrics.Format format = parseFormat(args);
            List<Path> sources = Metrics.projectFiles(context.projectRoot());
            CompilerModel.Model hit = cached(context, sources);
            if (hit != null) {
                // Findings, rankings and formatting are recomputed from the cached
                // measurements, so a changed threshold takes effect on the next run rather
                // than on the next cache miss.
                System.out.print(Metrics.of(sources.size(), hit,
                    SourceMetrics.measure(context.projectRoot(), sources)).render(format));
                return;
            }
            System.exit(spawnBridge(context, args));
        } catch (Usage | Metrics.Failure e) {
            System.err.println("metrics: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("metrics: " + e.getMessage());
            System.exit(2);
        }
    }

    /** The second phase: this JVM has the compiler on its class path. */
    private static void bridge(String[] args) {
        try {
            Context context = Context.read();
            List<Path> sources = Metrics.projectFiles(context.projectRoot());
            // Resolved here, in the only JVM that has a compiler on its class path. The
            // adapter is what knows Flix's internals; nothing else in this plugin does.
            CompilerModel model = Adapters.resolve();
            if (model == null)
                throw new Usage("no adapter links against the pinned compiler\n"
                    + "       this build supports: " + String.join(", ", Adapters.known()));
            CompilerModel.Model m = model.measure(context.projectRoot());
            // The measurements, written before anything is derived from them.
            if (context.pluginCache() != null)
                ResultCache.write(context.pluginCache(),
                    ResultCache.key(context, sources, version()), Wire.encode(m));
            SourceMetrics text = SourceMetrics.measure(context.projectRoot(), sources);
            Metrics.Report report = Metrics.of(sources.size(), m, text);
            // Written before rendering, and by this phase rather than the outer one. The outer
            // phase inherits this process's stdout, so it never sees the report as a value --
            // and parsing it back out of a stream the compiler also writes to would be reading
            // our own output past whatever Flix chose to print alongside it.
            System.out.print(report.render(parseFormat(args)));
        } catch (Usage | Metrics.Failure | CompilerModel.ModelFailure e) {
            System.err.println("metrics: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("metrics: " + e.getMessage());
            System.exit(2);
        }
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
    private static CompilerModel.Model cached(Context context, List<Path> sources) {
        if (context.pluginCache() == null) return null;
        String text = ResultCache.read(context.pluginCache(),
            ResultCache.key(context, sources, version()));
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
            Path plugin = Path.of(Main.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
            List<String> command = new ArrayList<>();
            command.add(context.java().toString());
            command.add("-cp");
            command.add(plugin + File.pathSeparator + context.compilerJar());
            command.add(Main.class.getName());
            command.add("--bridge");
            command.addAll(Arrays.asList(args));
            return new ProcessBuilder(command).directory(context.projectRoot().toFile())
                .inheritIO().start().waitFor();
        } catch (URISyntaxException | IOException e) {
            throw new Usage("cannot start reflection bridge: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Usage("interrupted while waiting for reflection bridge");
        }
    }

    private static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }

    private static Metrics.Format parseFormat(String[] args) {
        List<String> rest = Arrays.asList(args);
        if (!rest.isEmpty() && "report".equals(rest.get(0))) rest = rest.subList(1, rest.size());
        if (rest.isEmpty()) return Metrics.Format.TEXT;
        if (rest.size() == 2 && "--format".equals(rest.get(0)))
            return Metrics.Format.parse(rest.get(1));
        throw new Usage("usage: ./flixw metrics [report] [--format text|json]");
    }

    private static void usage() {
        System.out.println("usage: ./flixw metrics [report] [--format text|json]\n"
            + "       ./flixw metrics capabilities\n\n"
            + "Reads typed compiler data through a supported reflective API; formats are text or json.\n"
            + "Results are cached under FLIXW_CACHE_HOME and reused until the sources, the\n"
            + "manifest, the pinned compiler or this plugin change.");
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
