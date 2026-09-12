package dev.flixw.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;

/** Runs the packaged two-JVM plugin cold and warm, then consumes both machine formats. */
public final class PluginIntegrationTest {
    private PluginIntegrationTest() { }

    public static void main(String[] args) throws Exception {
        Path project = Flix075AdapterTest.copyFixture(Path.of(args[0]));
        Path cache = Files.createTempDirectory("flixw-metrics-integration-cache-");
        try {
            Path plugin = Path.of(args[1]);
            Path compiler = Path.of(args[2]);
            verifyArtifact(plugin);
            String cold = run(project, cache, plugin, compiler, System.getProperty("java.home"),
                "json");
            require(cold.trim().startsWith("{") && cold.trim().endsWith("}"),
                "cold stdout is one JSON object");
            require(cold.contains("\"definitions\"") && cold.contains("\"provenance\"")
                    && cold.contains("\"configuration\"") && cold.contains("\"inputDigest\"")
                    && cold.contains("\"compilerArtifact\""),
                "cold JSON contains measurements and reproducibility metadata");
            try (var entries = Files.list(cache)) {
                require(entries.filter(p -> p.toString().endsWith(".measurements")).count() == 1,
                    "a cold run writes one measurement cache entry");
            }

            String warm = run(project, cache, plugin, compiler,
                project.resolve("no-such-java-home").toString(), "json");
            require(withoutTime(cold).equals(withoutTime(warm)),
                "a warm report equals the cold report apart from its fresh timestamp");
            String sarif = run(project, cache, plugin, compiler,
                project.resolve("no-such-java-home").toString(), "sarif");
            require(sarif.contains("\"version\": \"2.1.0\"")
                    && sarif.contains("\"partialFingerprints\"")
                    && sarif.contains("\"configuration\""),
                "warm cached measurements render valid-shaped, attributable SARIF");
            System.out.println("PluginIntegrationTest: ok");
        } finally {
            Flix075AdapterTest.delete(project);
            Flix075AdapterTest.delete(cache);
        }
    }

    private static void verifyArtifact(Path plugin) throws Exception {
        try (JarFile jar = new JarFile(plugin.toFile())) {
            require(jar.stream().noneMatch(entry -> entry.getName().startsWith("scala/")
                    || entry.getName().startsWith("ca/uwaterloo/flix/")),
                "the plugin does not bundle compiler or Scala classes");
            var attributes = jar.getManifest().getMainAttributes();
            require("dev.flixw.metrics.Main".equals(attributes.getValue("Main-Class"))
                    && "metrics".equals(attributes.getValue("Flixw-Plugin-Command")),
                "the packaged plugin preserves its executable and flixw manifests");
        }
    }

    private static String run(Path project, Path cache, Path plugin, Path compiler,
                              String javaHome, String format) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
            "-jar", plugin.toString(), "report", "--format", format);
        Map<String, String> env = builder.environment();
        env.put("FLIXW_ABI_VERSION", "1");
        env.put("FLIXW_PROJECT_ROOT", project.toString());
        env.put("FLIXW_COMPILER_JAR", compiler.toString());
        env.put("FLIXW_JAVA_HOME", javaHome);
        env.put("FLIXW_PLUGIN_CACHE", cache.toString());
        Path errors = Files.createTempFile("flixw-metrics-stderr-", ".txt");
        try {
            Process process = builder.redirectError(errors.toFile()).start();
            String stdout = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            int status = process.waitFor();
            if (status != 0)
                throw new AssertionError("plugin exited " + status + ": " + Files.readString(errors));
            return stdout;
        } finally {
            Files.deleteIfExists(errors);
        }
    }

    private static String withoutTime(String json) {
        return json.replaceAll("\"measuredAt\": \\\"[^\\\"]+\\\"", "\"measuredAt\": \"TIME\"");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
