package dev.flixw.metrics;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
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
            System.out.println("flixw-metrics " + version());
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
            ReflectionEngine.Format format = parseFormat(args);
            ReflectionEngine.Report hit = cached(context);
            if (hit != null) {
                System.out.print(hit.render(format));
                return;
            }
            System.exit(spawnBridge(context, args));
        } catch (Usage | ReflectionEngine.Failure e) {
            System.err.println("flixw-metrics: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("flixw-metrics: " + e.getMessage());
            System.exit(2);
        }
    }

    /** The second phase: this JVM has the compiler on its class path. */
    private static void bridge(String[] args) {
        try {
            Context context = Context.read();
            List<Path> sources = ReflectionEngine.projectFiles(context.projectRoot());
            ReflectionEngine.Report report = ReflectionEngine.measure(context, sources);
            // Written before rendering, and by this phase rather than the outer one. The outer
            // phase inherits this process's stdout, so it never sees the report as a value --
            // and parsing it back out of a stream the compiler also writes to would be reading
            // our own output past whatever Flix chose to print alongside it.
            if (context.cacheHome() != null)
                ResultCache.write(context.cacheHome(),
                    ResultCache.key(context, sources, version()),
                    report.render(ReflectionEngine.Format.JSON));
            System.out.print(report.render(parseFormat(args)));
        } catch (Usage | ReflectionEngine.Failure e) {
            System.err.println("flixw-metrics: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("flixw-metrics: " + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * The cached report for these exact inputs, or null.
     *
     * <p>Every failure here returns null, which costs a recomputation and never a wrong
     * answer. That asymmetry is the whole design rule for this cache: it may only ever make
     * the plugin faster, never make it disagree with the compiler.
     */
    private static ReflectionEngine.Report cached(Context context) throws IOException {
        if (context.cacheHome() == null) return null;
        List<Path> sources = ReflectionEngine.projectFiles(context.projectRoot());
        String json = ResultCache.read(context.cacheHome(),
            ResultCache.key(context, sources, version()));
        return json == null ? null : ReflectionEngine.Report.fromJson(json);
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

    private static ReflectionEngine.Format parseFormat(String[] args) {
        List<String> rest = Arrays.asList(args);
        if (!rest.isEmpty() && "report".equals(rest.get(0))) rest = rest.subList(1, rest.size());
        if (rest.isEmpty()) return ReflectionEngine.Format.TEXT;
        if (rest.size() == 2 && "--format".equals(rest.get(0)))
            return ReflectionEngine.Format.parse(rest.get(1));
        throw new Usage("usage: ./flixw plugin flixw-metrics [report] [--format text|json]");
    }

    private static void usage() {
        System.out.println("usage: ./flixw plugin flixw-metrics [report] [--format text|json]\n"
            + "       ./flixw plugin flixw-metrics capabilities\n\n"
            + "Reads typed compiler data through a supported reflective API; formats are text or json.\n"
            + "Results are cached under FLIXW_CACHE_HOME and reused until the sources, the\n"
            + "manifest, the pinned compiler or this plugin change.");
    }

    record Context(Path projectRoot, Path compilerJar, Path java, Path cacheHome) {
        static Context read() {
            if (!"1".equals(System.getenv("FLIXW_ABI_VERSION")))
                throw new Usage("requires flixw ABI version 1");
            // Optional, unlike the rest: the ABI provides it, but a report is still correct
            // without a cache, so a missing value disables caching instead of failing the run.
            String cache = System.getenv("FLIXW_CACHE_HOME");
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
