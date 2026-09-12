package dev.flixw.metrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** Runs the packaged two-JVM plugin cold and warm, then consumes both machine formats. */
public final class PluginIntegrationTest {
    private PluginIntegrationTest() { }

    public static void main(String[] args) throws Exception {
        Path project = Flix075AdapterTest.copyFixture(Path.of(args[0]));
        Path initProject = Flix075AdapterTest.copyFixture(Path.of(args[0]));
        Path cache = Files.createTempDirectory("flixw-metrics-integration-cache-");
        Path initCache = Files.createTempDirectory("flixw-metrics-init-cache-");
        try {
            Path plugin = Path.of(args[1]);
            Path compiler = Path.of(args[2]);
            String initialized = runCommand(initProject, initCache, plugin, compiler,
                System.getProperty("java.home"), "init");
            require(initialized.contains("--fail-on-new warning")
                    && Files.isRegularFile(initProject.resolve(MetricsConfig.FILE))
                    && Files.isRegularFile(initProject.resolve(Initializer.BASELINE_FILE)),
                "packaged init creates policy and baseline and prints the CI gate");
            JsonObject initializedBaseline = JsonParser.parseString(Files.readString(
                initProject.resolve(Initializer.BASELINE_FILE))).getAsJsonObject();
            require(initializedBaseline.has("provenance")
                    && initializedBaseline.has("configuration")
                    && initializedBaseline.has("smells"),
                "init captures the complete native report through the compiler pipeline");
            String policyBefore = Files.readString(initProject.resolve(MetricsConfig.FILE));
            String baselineBefore = Files.readString(initProject.resolve(Initializer.BASELINE_FILE));
            runCommandExpecting(initProject, initCache, plugin, compiler,
                System.getProperty("java.home"), 2, "init");
            require(Files.readString(initProject.resolve(MetricsConfig.FILE)).equals(policyBefore)
                    && Files.readString(initProject.resolve(Initializer.BASELINE_FILE))
                        .equals(baselineBefore),
                "packaged init refuses to overwrite either onboarding artifact");

            Path generated = project.resolve("src/generated/Data.flix");
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, "pub def generated(): String = \"" + "x".repeat(200)
                + "\"\n");
            Files.writeString(project.resolve(MetricsConfig.FILE), """
                exclusions.generated.file=src/generated/**
                exclusions.generated.reason=generated integration fixture
                """);
            verifyArtifact(plugin);
            String cold = run(project, cache, plugin, compiler, System.getProperty("java.home"),
                "json");
            JsonObject coldJson = JsonParser.parseString(cold).getAsJsonObject();
            require(coldJson.getAsJsonObject("summary").get("definitions").getAsInt() == 7,
                "cold stdout parses as one report with fixture measurements");
            require(coldJson.getAsJsonObject("summary").get("excludedFiles").getAsInt() == 1
                    && coldJson.getAsJsonArray("excludedSources").size() == 1
                    && coldJson.getAsJsonArray("smells").asList().stream().noneMatch(element ->
                        element.getAsJsonObject().get("file").getAsString().contains("generated")),
                "packaged reports compile but exclude a configured generated source");
            require(coldJson.getAsJsonObject("summary").get("datalogRules").getAsInt() == 1
                    && coldJson.getAsJsonObject("summary").get("datalogFacts").getAsInt() == 1,
                "packaged reports preserve separate Datalog rule and fact counts");
            require(coldJson.has("provenance") && coldJson.has("configuration")
                    && coldJson.getAsJsonObject("provenance").has("inputDigest")
                    && coldJson.getAsJsonObject("provenance").has("compilerArtifact"),
                "cold JSON contains measurements and reproducibility metadata");
            try (var entries = Files.list(cache)) {
                require(entries.filter(p -> p.toString().endsWith(".measurements")).count() == 1,
                    "a cold run writes one measurement cache entry");
            }

            String warm = run(project, cache, plugin, compiler,
                project.resolve("no-such-java-home").toString(), "json");
            JsonObject warmJson = JsonParser.parseString(warm).getAsJsonObject();
            require(withoutTime(coldJson).equals(withoutTime(warmJson)),
                "a warm report equals the cold report apart from its fresh timestamp");

            Path baseline = project.resolve("metrics-baseline.json");
            Files.writeString(baseline, cold);
            String comparedWarm = run(project, cache, plugin, compiler,
                project.resolve("no-such-java-home").toString(), "json",
                "--baseline", baseline.toString(), "--fail-on-new", "warning");
            JsonObject comparison = JsonParser.parseString(comparedWarm).getAsJsonObject()
                .getAsJsonObject("baseline");
            require(comparison.get("newCount").getAsInt() == 0
                    && comparison.get("worsenedCount").getAsInt() == 0
                    && comparison.get("resolvedCount").getAsInt() == 0,
                "an unchanged warm run passes a new-findings gate");

            JsonObject emptyBaseline = coldJson.deepCopy();
            emptyBaseline.add("smells", new com.google.gson.JsonArray());
            Files.writeString(baseline, emptyBaseline.toString());
            String failed = runExpecting(project, cache, plugin, compiler,
                project.resolve("no-such-java-home").toString(), "json", 1,
                "--baseline", baseline.toString(), "--fail-on-new", "note");
            require(JsonParser.parseString(failed).getAsJsonObject().getAsJsonObject("baseline")
                    .get("newCount").getAsInt() > 0,
                "a failed baseline gate still writes the complete parseable report");
            String sarif = run(project, cache, plugin, compiler,
                project.resolve("no-such-java-home").toString(), "sarif");
            JsonObject sarifJson = JsonParser.parseString(sarif).getAsJsonObject();
            JsonObject run = sarifJson.getAsJsonArray("runs").get(0).getAsJsonObject();
            require("2.1.0".equals(sarifJson.get("version").getAsString())
                    && run.getAsJsonObject("properties").has("configuration")
                    && run.getAsJsonArray("results").get(0).getAsJsonObject()
                        .has("partialFingerprints"),
                "warm cached measurements render parseable, attributable SARIF");
            System.out.println("PluginIntegrationTest: ok");
        } finally {
            Flix075AdapterTest.delete(project);
            Flix075AdapterTest.delete(initProject);
            Flix075AdapterTest.delete(cache);
            Flix075AdapterTest.delete(initCache);
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
                              String javaHome, String format, String... extra) throws Exception {
        return runExpecting(project, cache, plugin, compiler, javaHome, format, 0, extra);
    }

    private static String runExpecting(Path project, Path cache, Path plugin, Path compiler,
                                       String javaHome, String format, int expected,
                                       String... extra) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of(
            System.getProperty("java.home") + "/bin/java", "-jar", plugin.toString(),
            "report", "--format", format));
        command.addAll(List.of(extra));
        ProcessBuilder builder = new ProcessBuilder(command);
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
            if (status != expected)
                throw new AssertionError("plugin exited " + status + " instead of " + expected
                    + ": " + Files.readString(errors));
            return stdout;
        } finally {
            Files.deleteIfExists(errors);
        }
    }

    private static String runCommand(Path project, Path cache, Path plugin, Path compiler,
                                     String javaHome, String... command) throws Exception {
        return runCommandExpecting(project, cache, plugin, compiler, javaHome, 0, command);
    }

    private static String runCommandExpecting(Path project, Path cache, Path plugin, Path compiler,
                                              String javaHome, int expected, String... command)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new java.util.ArrayList<>(List.of(
            System.getProperty("java.home") + "/bin/java", "-jar", plugin.toString())));
        builder.command().addAll(List.of(command));
        Map<String, String> env = builder.environment();
        env.put("FLIXW_ABI_VERSION", "1");
        env.put("FLIXW_PROJECT_ROOT", project.toString());
        env.put("FLIXW_COMPILER_JAR", compiler.toString());
        env.put("FLIXW_JAVA_HOME", javaHome);
        env.put("FLIXW_PLUGIN_CACHE", cache.toString());
        Path errors = Files.createTempFile("flixw-metrics-init-stderr-", ".txt");
        try {
            Process process = builder.redirectError(errors.toFile()).start();
            String stdout = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            int status = process.waitFor();
            if (status != expected)
                throw new AssertionError("plugin exited " + status + " instead of " + expected
                    + ": " + Files.readString(errors));
            return stdout;
        } finally {
            Files.deleteIfExists(errors);
        }
    }

    private static JsonObject withoutTime(JsonObject json) {
        JsonObject copy = json.deepCopy();
        copy.getAsJsonObject("provenance").remove("measuredAt");
        return copy;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
