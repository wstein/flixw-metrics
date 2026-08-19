package dev.flixw.metrics;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Facts established by inspecting, but not initializing, classes in the pinned compiler JAR. */
record CompilerCapabilities(boolean hasFlixApi, boolean hasReflectionApi, boolean hasNativeMetrics,
                            List<String> missing) {

    /**
     * The gate must name the members the engine actually reflects against.
     *
     * <p>It previously checked {@code Flix.check()} and a two-argument {@code addFile} -- and
     * {@link ReflectionEngine} calls neither {@code addFile} nor anything else on that list.
     * A compiler that kept {@code addFile} while changing {@code Bootstrap.bootstrap} passed
     * the gate and then died inside the engine with a reflection error, which is precisely the
     * failure the gate exists to turn into a sentence. Every entry below is a member the
     * engine calls; if the engine starts calling something else, it belongs here too.
     */
    static CompilerCapabilities inspect(ClassLoader compiler, Path compilerJar) {
        if (!Files.isRegularFile(compilerJar))
            throw new Main.Usage("FLIXW_COMPILER_JAR is not a regular file: " + compilerJar);
        boolean flix = present(compiler, "ca.uwaterloo.flix.api.Flix");
        List<String> missing = new ArrayList<>();

        requireClass(compiler, missing, "ca.uwaterloo.flix.api.Flix");
        requireClass(compiler, missing, "ca.uwaterloo.flix.api.Bootstrap");
        requireClass(compiler, missing, "ca.uwaterloo.flix.util.Options$");
        requireClass(compiler, missing, "ca.uwaterloo.flix.util.Formatter$");
        requireMethod(compiler, missing, "ca.uwaterloo.flix.api.Flix", "check", 0);
        requireMethod(compiler, missing, "ca.uwaterloo.flix.api.Flix", "setOptions", 1);
        requireMethod(compiler, missing, "ca.uwaterloo.flix.api.Bootstrap", "check", 1);
        requireMethod(compiler, missing, "ca.uwaterloo.flix.util.Options$", "Default", 0);
        requireMethod(compiler, missing, "ca.uwaterloo.flix.util.Formatter$", "getDefault", 0);
        requireBootstrapEntry(compiler, missing);

        return new CompilerCapabilities(flix, missing.isEmpty(),
            present(compiler, "ca.uwaterloo.flix.tools.Metrics$"), List.copyOf(missing));
    }

    /**
     * {@code Bootstrap.bootstrap} is checked by shape rather than by exact parameter types.
     *
     * <p>Its signature mentions {@code scala.Option} and Flix's own {@code Formatter}, so an
     * exact-match lookup would have to load and name those types to ask the question. Arity
     * plus a static modifier plus the leading {@link Path} is enough to tell "this compiler
     * has the entry point the engine calls" from "it does not", which is all the gate claims.
     */
    private static void requireBootstrapEntry(ClassLoader compiler, List<String> missing) {
        try {
            Class<?> type = Class.forName("ca.uwaterloo.flix.api.Bootstrap", false, compiler);
            for (var method : type.getMethods()) {
                if (!method.getName().equals("bootstrap") || method.getParameterCount() != 4) continue;
                if (method.getParameterTypes()[0] == Path.class
                    && method.getParameterTypes()[3] == PrintStream.class) return;
            }
            missing.add("static ca.uwaterloo.flix.api.Bootstrap.bootstrap(Path, ..., PrintStream)");
        } catch (ClassNotFoundException e) {
            // The class itself is already reported by requireClass; do not say it twice.
        }
    }

    private static void requireClass(ClassLoader loader, List<String> missing, String name) {
        if (!present(loader, name)) missing.add("class " + name);
    }

    private static void requireMethod(ClassLoader loader, List<String> missing, String owner,
                                      String name, int arity) {
        try {
            Class<?> type = Class.forName(owner, false, loader);
            for (var method : type.getMethods())
                if (method.getName().equals(name) && method.getParameterCount() == arity) return;
            missing.add(owner + "." + name + "/" + arity);
        } catch (ClassNotFoundException e) {
            // Reported by requireClass.
        }
    }

    private static boolean present(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    String json(Main.Context context) {
        StringBuilder b = new StringBuilder("{\n");
        b.append("  \"compilerJar\": ").append(quote(context.compilerJar().toString())).append(",\n");
        b.append("  \"hasFlixApi\": ").append(hasFlixApi).append(",\n");
        b.append("  \"hasReflectionApi\": ").append(hasReflectionApi).append(",\n");
        b.append("  \"hasNativeMetrics\": ").append(hasNativeMetrics).append(",\n");
        b.append("  \"missing\": [");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(quote(missing.get(i)));
        }
        return b.append("]\n}").toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
