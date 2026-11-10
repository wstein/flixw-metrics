package dev.flixw.metrics;

import dev.flixw.sdk.bridge.CompilerCapabilities;
import java.nio.file.Path;
import java.util.List;

/**
 * What this plugin adds to the SDK's compiler inspection: the probe for the compiler's own
 * {@code Metrics} object and the rendering of the {@code capabilities} command.
 *
 * <p>The contract check itself, the one over the bytecode-derived requirements of every adapter, is
 * {@link CompilerCapabilities#inspect} in {@code flix-bridge}. This facade keeps the two plugin
 * concerns out of the SDK: the native-Metrics probe names a class only this plugin cares about, and
 * the JSON is the plugin's own command output.
 */
final class CompilerFacade {
    private CompilerFacade() { }

    /** Facts established by inspecting, but not initializing, classes in the pinned compiler JAR. */
    record Capabilities(boolean hasFlixApi, boolean hasEngineApi, boolean hasNativeMetrics,
                        List<String> missing) {
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
    }

    /** The SDK's contract check plus the native-Metrics probe. */
    static Capabilities inspect(ClassLoader compiler, Path compilerJar) {
        CompilerCapabilities found;
        try {
            found = CompilerCapabilities.inspect(compiler, compilerJar);
        } catch (dev.flixw.sdk.kit.Usage e) {
            throw new Main.Usage(e.getMessage());
        }
        return new Capabilities(found.hasFlixApi(), found.hasEngineApi(),
            CompilerCapabilities.present(compiler, "ca.uwaterloo.flix.tools.Metrics$"), found.missing());
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
