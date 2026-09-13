package dev.flixw.metrics;

import dev.flixw.metrics.sdk.AdapterAbi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Facts established by inspecting, but not initializing, classes in the pinned compiler JAR. */
record CompilerCapabilities(boolean hasFlixApi, boolean hasEngineApi, boolean hasNativeMetrics,
                            List<String> missing) {

    /**
     * The gate must name what the engine actually links against.
     *
     * <p>Since the engine moved to Scala the gate matters more, not less. A reflective engine
     * limped along and produced something; a linked one throws {@code NoSuchMethodError} from
     * inside the JVM's verifier, with a message written for whoever wrote the JVM. Everything
     * in a bytecode-derived contract is a type or member an adapter binds to at compile time, so
     * a compiler that fails every contract is one the engine could not have run against -- and it
     * is told so in a sentence instead.
     *
     * <p>This class stays Java for exactly that reason. It has to load and answer on a machine
     * where none of the adapters would link at all.
     */
    static CompilerCapabilities inspect(ClassLoader compiler, Path compilerJar) {
        if (!Files.isRegularFile(compilerJar))
            throw new Main.Usage("FLIXW_COMPILER_JAR is not a regular file: " + compilerJar);
        boolean flix = present(compiler, "ca.uwaterloo.flix.api.Flix");
        List<List<String>> failures = AdapterAbi.contracts().stream()
            .map(contract -> AdapterAbi.missing(contract, compiler)).toList();
        List<String> missing = failures.stream().anyMatch(List::isEmpty) ? List.of()
            : failures.stream().min(Comparator.comparingInt(List::size)).orElseGet(List::of);
        if (!present(compiler, "dev.flix.runtime.Global")) {
            ArrayList<String> withRuntime = new ArrayList<>(missing);
            withRuntime.add("class dev.flix.runtime.Global");
            missing = List.copyOf(withRuntime);
        }

        return new CompilerCapabilities(flix, missing.isEmpty(),
            present(compiler, "ca.uwaterloo.flix.tools.Metrics$"), List.copyOf(missing));
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
        b.append("  \"hasEngineApi\": ").append(hasEngineApi).append(",\n");
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
