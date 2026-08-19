package dev.flixw.metrics;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Facts established by inspecting, but not initializing, classes in the pinned compiler JAR. */
record CompilerCapabilities(boolean hasFlixApi, boolean hasNativeMetrics) {
    static CompilerCapabilities inspect(Path compilerJar) {
        if (!Files.isRegularFile(compilerJar))
            throw new Main.Usage("FLIXW_COMPILER_JAR is not a regular file: " + compilerJar);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {compilerJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            return new CompilerCapabilities(present(loader, "ca.uwaterloo.flix.api.Flix"),
                present(loader, "ca.uwaterloo.flix.tools.Metrics$"));
        } catch (IOException e) {
            throw new Main.Usage("cannot inspect compiler jar " + compilerJar + ": " + e.getMessage());
        }
    }

    private static boolean present(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    String json(Main.Context context) {
        return "{\n"
            + "  \"compilerJar\": " + quote(context.compilerJar().toString()) + ",\n"
            + "  \"hasFlixApi\": " + hasFlixApi + ",\n"
            + "  \"hasNativeMetrics\": " + hasNativeMetrics + "\n"
            + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
