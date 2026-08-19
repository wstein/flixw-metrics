package dev.flixw.metrics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Entry point for the flixw compiler-metrics plugin. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            usage();
            return;
        }
        if (args.length == 1 && "--version".equals(args[0])) {
            String version = Main.class.getPackage().getImplementationVersion();
            System.out.println("flix-metrics " + (version == null ? "development" : version));
            return;
        }
        try {
            Context context = Context.read();
            CompilerCapabilities capabilities = CompilerCapabilities.inspect(context.compilerJar());
            if (args.length == 1 && "capabilities".equals(args[0])) {
                System.out.println(capabilities.json(context));
                return;
            }
            if (!capabilities.hasNativeMetrics()) {
                throw new Usage("the pinned compiler has no supported metrics model\n"
                    + "       jar: " + context.compilerJar() + "\n"
                    + "       expected: ca.uwaterloo.flix.tools.Metrics and a metric command\n"
                    + "       this plugin refuses to substitute source-scanned metrics");
            }
            List<String> metricArgs = new ArrayList<>(Arrays.asList(args));
            if (!metricArgs.isEmpty() && "report".equals(metricArgs.get(0))) metricArgs.remove(0);
            System.exit(runNativeMetric(context, metricArgs));
        } catch (Usage e) {
            System.err.println("flix-metrics: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("flix-metrics: cannot run metric: " + e.getMessage());
            System.exit(2);
        }
    }

    private static int runNativeMetric(Context context, List<String> args)
        throws IOException {
        List<String> command = new ArrayList<>();
        command.add(context.java().toString());
        command.add("-jar");
        command.add(context.compilerJar().toString());
        command.add("metric");
        command.addAll(args);
        try {
            return new ProcessBuilder(command).directory(context.projectRoot().toFile()).inheritIO().start()
                .waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for the compiler", e);
        }
    }

    private static void usage() {
        System.out.println("usage: ./flixw plugin flix-metrics [report] [metric options...]\n"
            + "       ./flixw plugin flix-metrics capabilities\n\n"
            + "Runs only against a compiler whose metric model this plugin recognizes.");
    }

    record Context(Path projectRoot, Path compilerJar, Path java) {
        static Context read() {
            if (!"1".equals(System.getenv("FLIXW_ABI_VERSION")))
                throw new Usage("requires flixw ABI version 1");
            return new Context(path("FLIXW_PROJECT_ROOT"), path("FLIXW_COMPILER_JAR"),
                path("FLIXW_JAVA_HOME").resolve("bin").resolve("java"));
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
