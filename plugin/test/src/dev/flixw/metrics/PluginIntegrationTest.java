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
        Path project = Flix0753AdapterTest.copyFixture(Path.of(args[0]));
        Path initProject = Flix0753AdapterTest.copyFixture(Path.of(args[0]));
        Path cache = Files.createTempDirectory("flixw-metrics-integration-cache-");
        Path initCache = Files.createTempDirectory("flixw-metrics-init-cache-");
        try {
            Path plugin = Path.of(args[1]);
            Path compiler = Path.of(args[2]);
            int expectedEffectSurface = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
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
            require(coldJson.getAsJsonObject("summary").get("datalogRules").getAsInt() == 3
                    && coldJson.getAsJsonObject("summary").get("datalogFacts").getAsInt() == 1,
                "packaged reports preserve separate Datalog rule and fact counts");
            require(coldJson.getAsJsonObject("summary").get("widestEffectSurface").getAsInt()
                    == expectedEffectSurface
                    && coldJson.getAsJsonObject("summary")
                        .get("widestDatalogDependencyBreadth").getAsInt() == 3,
                "packaged reports summarize effect and Datalog dependency breadth");
            require(coldJson.getAsJsonObject("summary")
                        .get("deepestDatalogDependency").getAsInt() == 2
                    && coldJson.getAsJsonObject("summary")
                        .get("mostRecursiveDatalogPredicates").getAsInt() == 2,
                "packaged reports summarize Datalog depth and recursion");
            JsonObject documented = coldJson.getAsJsonArray("definitions").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(definition -> definition.get("name").getAsString()
                    .equals("Alpha.documented"))
                .findFirst().orElseThrow();
            require(documented.get("flixdocParameterCharacters").getAsInt() == 20
                    && documented.get("flixdocResultCharacters").getAsInt() == 14
                    && documented.get("parameterDocEntries").getAsInt() == 2
                    && documented.get("redundantParameterDocEntries").getAsInt() == 2
                    && coldJson.getAsJsonArray("smells").asList().stream().anyMatch(element ->
                        element.getAsJsonObject().get("rule").getAsString()
                            .equals("redundant-parameter-doc")),
                "packaged JSON carries both FlixDoc metrics and their conservative finding");
            require(coldJson.getAsJsonArray("rankings").asList().stream().anyMatch(element ->
                    element.getAsJsonObject().get("measure").getAsString()
                        .equals("longest-flixdoc-result")),
                "the packaged report ranks compiler-rendered FlixDoc result types");
            require(coldJson.getAsJsonArray("rankings").asList().stream().anyMatch(element -> {
                JsonObject rank = element.getAsJsonObject();
                return rank.get("measure").getAsString().equals("highest-change-impact")
                    && rank.get("subject").getAsString().equals("Alpha")
                    && rank.get("value").getAsString().contains("2 dependent modules");
            }), "the packaged report ranks resolved definition-call fan-in");
            JsonObject datalog = coldJson.getAsJsonArray("definitions").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(definition -> definition.get("name").getAsString().equals("Alpha.datalog"))
                .findFirst().orElseThrow();
            require(datalog.get("effectCount").getAsInt() == 0
                    && datalog.get("datalogDependencyBreadth").getAsInt() == 3
                    && datalog.getAsJsonArray("datalogDependencies").size() == 3
                    && datalog.get("datalogDependencyDepth").getAsInt() == 2
                    && datalog.get("recursiveDatalogPredicateCount").getAsInt() == 2
                    && datalog.getAsJsonArray("recursiveDatalogPredicates").get(0)
                        .getAsString().equals("Path")
                    && datalog.getAsJsonArray("recursiveDatalogPredicates").get(1)
                        .getAsString().equals("Reach"),
                "native definition facts expose both semantic breadth measurements");
            require(coldJson.getAsJsonArray("rankings").asList().stream().anyMatch(element ->
                    element.getAsJsonObject().get("measure").getAsString()
                        .equals("widest-datalog-dependency")),
                "the packaged report ranks Datalog dependency breadth");
            require(coldJson.getAsJsonArray("rankings").asList().stream().anyMatch(element ->
                    element.getAsJsonObject().get("measure").getAsString()
                        .equals("deepest-datalog-dependency"))
                    && coldJson.getAsJsonArray("rankings").asList().stream().anyMatch(element ->
                        element.getAsJsonObject().get("measure").getAsString()
                            .equals("most-recursive-datalog")),
                "the packaged report ranks Datalog dependency depth and recursion");
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

            String filtered = run(project, cache, plugin, compiler,
                project.resolve("no-such-java-home").toString(), "json",
                "--view", "findings", "--rule", "redundant-parameter-doc",
                "--severity", "note", "--file", "src/**");
            JsonObject filteredJson = JsonParser.parseString(filtered).getAsJsonObject();
            require(filteredJson.has("presentationFilter")
                    && filteredJson.getAsJsonArray("smells").size() == 1
                    && filteredJson.getAsJsonArray("smells").get(0).getAsJsonObject()
                        .get("rule").getAsString().equals("redundant-parameter-doc"),
                "packaged CLI composes rule, minimum-severity and file presentation filters");
            String gatedDespiteFilter = runExpecting(project, cache, plugin, compiler,
                project.resolve("no-such-java-home").toString(), "json", 1,
                "--view", "findings", "--rule", "line-too-long", "--fail-on", "note");
            require(JsonParser.parseString(gatedDespiteFilter).getAsJsonObject()
                    .getAsJsonArray("smells").size() == 0,
                "a filter can hide output while the complete report still drives the gate");

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
            Flix0753AdapterTest.delete(project);
            Flix0753AdapterTest.delete(initProject);
            Flix0753AdapterTest.delete(cache);
            Flix0753AdapterTest.delete(initCache);
        }
    }

    private static void verifyArtifact(Path plugin) throws Exception {
        try (JarFile jar = new JarFile(plugin.toFile())) {
            require(jar.stream().noneMatch(entry -> entry.getName().startsWith("scala/")
                    || entry.getName().startsWith("ca/uwaterloo/flix/")),
                "the plugin does not bundle compiler or Scala classes");
            require(jar.getEntry("dev/flixw/metrics/flix0680/Flix0680Adapter.class") != null
                    && jar.getEntry("dev/flixw/metrics/flix0672/Flix0672Adapter.class") != null
                    && jar.getEntry("dev/flixw/metrics/flix0661/Flix0661Adapter.class") != null
                    && jar.getEntry("dev/flixw/metrics/flix0753/Flix0753Adapter.class") != null,
                "the plugin packages every compiler adapter family");
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
