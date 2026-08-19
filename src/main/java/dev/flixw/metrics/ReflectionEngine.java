package dev.flixw.metrics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately small semantic metrics engine over Flix's runtime-checked typed root. */
final class ReflectionEngine {
    private ReflectionEngine() { }

    enum Format {
        TEXT, JSON;

        static Format parse(String value) {
            return switch (value) {
                case "text" -> TEXT;
                case "json" -> JSON;
                default -> throw new Main.Usage("unknown format " + value + " (expected text or json)");
            };
        }
    }

    /**
     * One report. Flat on purpose: every field is a count a consumer can compare between two
     * runs without knowing what the others mean.
     */
    record Report(int files, int modules, int definitions, int localDefinitions,
                  int effectfulDefinitions, int branches, int traits, int instances, int enums,
                  int structs, int effects, int typeAliases, int lines, int longestLine,
                  int linesOverLimit, List<SourceMetrics.Smell> smells) {

        static final int SCHEMA = 2;

        String render(Format format) {
            return format == Format.JSON ? json() : text();
        }

        private String json() {
            StringBuilder b = new StringBuilder("{\n");
            b.append("  \"schemaVersion\": ").append(SCHEMA).append(",\n");
            for (String[] pair : fields()) b.append("  \"").append(pair[0]).append("\": ")
                                            .append(pair[1]).append(",\n");
            b.append("  \"smells\": [");
            for (int i = 0; i < smells.size(); i++) {
                b.append(i == 0 ? "\n" : ",\n").append("    ").append(smells.get(i).json());
            }
            b.append(smells.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
            return b.toString();
        }

        private String text() {
            StringBuilder b = new StringBuilder();
            for (String[] pair : fields()) b.append(pair[0]).append(": ").append(pair[1]).append('\n');
            b.append("smells: ").append(smells.size()).append('\n');
            for (SourceMetrics.Smell smell : smells) b.append(smell.text()).append('\n');
            return b.toString();
        }

        /** The order the two renderers share, so they cannot drift apart field by field. */
        private String[][] fields() {
            return new String[][] {
                {"files", "" + files}, {"modules", "" + modules},
                {"definitions", "" + definitions}, {"localDefinitions", "" + localDefinitions},
                {"effectfulDefinitions", "" + effectfulDefinitions}, {"branches", "" + branches},
                {"traits", "" + traits}, {"instances", "" + instances}, {"enums", "" + enums},
                {"structs", "" + structs}, {"effects", "" + effects},
                {"typeAliases", "" + typeAliases}, {"lines", "" + lines},
                {"longestLine", "" + longestLine}, {"linesOverLimit", "" + linesOverLimit},
            };
        }

        /**
         * Reads back what {@link #json} wrote, or null if it is not that.
         *
         * <p>This parses one fixed shape this class emitted itself, which is why scanning for
         * {@code "name": <integer>} is enough and a JSON library would be a dependency bought
         * for nothing. Null on anything unexpected -- a truncated file, a hand-edited entry, a
         * {@code schemaVersion} this build does not know -- and the caller recomputes. A cache
         * entry is an optimisation, so the only wrong answer here is a confident one.
         *
         * <p>Smells are not read back. They are derived from the same inputs as the counts, so
         * an entry carrying counts without them would be a partial report; a cached entry is
         * therefore only reusable in full, and {@link #smellsFromJson} rebuilds the list.
         */
        static Report fromJson(String json) {
            Integer schema = field(json, "schemaVersion");
            if (schema == null || schema != SCHEMA) return null;
            int[] v = new int[15];
            String[] names = {"files", "modules", "definitions", "localDefinitions",
                "effectfulDefinitions", "branches", "traits", "instances", "enums", "structs",
                "effects", "typeAliases", "lines", "longestLine", "linesOverLimit"};
            for (int i = 0; i < names.length; i++) {
                Integer n = field(json, names[i]);
                if (n == null) return null;
                v[i] = n;
            }
            List<SourceMetrics.Smell> smells = smellsFromJson(json);
            if (smells == null) return null;
            return new Report(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9],
                v[10], v[11], v[12], v[13], v[14], smells);
        }

        private static List<SourceMetrics.Smell> smellsFromJson(String json) {
            List<SourceMetrics.Smell> out = new ArrayList<>();
            Matcher m = Pattern.compile(
                "\\{\\s*\"rule\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"file\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\","
              + "\\s*\"line\":\\s*(\\d+),\\s*\"detail\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}")
                .matcher(json);
            while (m.find())
                out.add(new SourceMetrics.Smell(unquote(m.group(1)), unquote(m.group(2)),
                    Integer.parseInt(m.group(3)), unquote(m.group(4))));
            return out;
        }

        private static String unquote(String s) {
            return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r")
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }

        private static Integer field(String json, String name) {
            Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
            if (!m.find()) return null;
            try {
                return Integer.valueOf(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    static Report measure(Main.Context context, List<Path> sources) {
        try {
            ClassLoader loader = ClassLoader.getSystemClassLoader();
            Class<?> flixClass = loader.loadClass("ca.uwaterloo.flix.api.Flix");
            Object flix = flixClass.getConstructor().newInstance();
            Object options = call(loader.loadClass("ca.uwaterloo.flix.util.Options$")
                .getField("MODULE$").get(null), "Default");
            flixClass.getMethod("setOptions", loader.loadClass("ca.uwaterloo.flix.util.Options"))
                .invoke(flix, options);
            Object bootstrap = bootstrap(loader, context.projectRoot());
            Object loaded = find(bootstrap.getClass(), "check", 1).invoke(bootstrap, flix);
            call(loaded, "unsafeGet");
            Object result = find(flixClass, "check", 0).invoke(flix);
            Object root = optionGet(call(result, "_1"));
            if (root == null)
                throw new Failure("the compiler produced no typed root; fix compilation errors first");

            Path projectRoot = context.projectRoot();
            List<Object> defs = projectValues(root, "defs", projectRoot);
            Walk walk = new Walk();
            for (Object def : defs) walk.visit(call(def, "exp"));
            SourceMetrics text = SourceMetrics.measure(projectRoot, sources);

            return new Report(sources.size(), modules(defs), defs.size(), walk.localDefs,
                effectful(defs), walk.branches,
                projectValues(root, "traits", projectRoot).size(),
                projectValues(root, "instances", projectRoot).size(),
                projectValues(root, "enums", projectRoot).size(),
                projectValues(root, "structs", projectRoot).size(),
                projectValues(root, "effects", projectRoot).size(),
                projectValues(root, "typeAliases", projectRoot).size(),
                text.lines(), text.longestLine(), text.linesOverLimit(), text.smells());
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new Failure("cannot read the pinned compiler's typed root: " + cause(e));
        }
    }

    /**
     * How many namespaces the project declares, from the symbols rather than the directory
     * layout: a file may hold several modules and a module may span files, so counting either
     * files or directories would answer a different question than the one asked.
     */
    private static int modules(List<Object> defs) throws ReflectiveOperationException {
        java.util.Set<String> namespaces = new java.util.TreeSet<>();
        for (Object def : defs) {
            String sym = call(def, "sym").toString();
            int dot = sym.lastIndexOf('.');
            namespaces.add(dot < 0 ? "" : sym.substring(0, dot));
        }
        return namespaces.size();
    }

    /**
     * Definitions whose signature admits an effect.
     *
     * <p>Asked of the declared effect rather than inferred from the body, because the declared
     * one is the promise the definition makes to its callers -- which is the thing worth
     * counting. "Pure" is matched by name: the type's own printed form is the only stable
     * handle reflection has on it, and a fork renaming Pure would rather under-report than
     * make this class refuse to run.
     */
    private static int effectful(List<Object> defs) throws ReflectiveOperationException {
        int count = 0;
        for (Object def : defs) {
            Object eff = call(call(def, "spec"), "eff");
            if (!"Pure".equals(eff.toString())) count++;
        }
        return count;
    }

    /**
     * Counts constructs by walking every node as a Scala {@code Product}.
     *
     * <p>Generic on purpose. Naming AST classes to cast to would couple this to a class list
     * that changes with the compiler; every Flix AST node is a case class, so
     * {@code productArity}/{@code productElement} reaches all of them and an unfamiliar node is
     * simply traversed rather than fatal. What is matched is the simple class name, which is
     * the most stable handle available -- and being wrong about one name loses a count, not the
     * run.
     *
     * <p>{@code LocalDef} is the one the outer signature hides: a definition nested inside
     * another is invisible to anything counting top-level symbols, and is exactly where
     * complexity accumulates unnoticed.
     */
    private static final class Walk {
        int localDefs;
        int branches;
        private final java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();

        void visit(Object node) {
            visit(node, 0);
        }

        private void visit(Object node, int depth) {
            // Types are cyclic through their own constructors and the AST is deep. The bound
            // and the identity set are both belt: a metrics run that never returns would be
            // worse than one that undercounts.
            if (node == null || depth > 200 || seen.put(node, Boolean.TRUE) != null) return;
            switch (node.getClass().getSimpleName()) {
                case "LocalDef" -> localDefs++;
                // Each is one more path through the definition. A `match` with three rules is
                // three branches, not one, which is why rules are counted and not the match.
                // Taken from the compiler's own rule types rather than from memory:
                // CatchRule ExtMatchRule HandlerRule MatchRule RestrictableChooseRule
                // SelectChannelRule. The first draft of this list named a TypeMatchRule that
                // does not exist and omitted ExtMatchRule that does, which undercounted in
                // silence -- the failure mode this whole approach is prone to, recorded here
                // because a name is the only handle it has.
                case "IfThenElse", "MatchRule", "ExtMatchRule", "RestrictableChooseRule",
                     "CatchRule", "HandlerRule", "SelectChannelRule" -> branches++;
                default -> { }
            }
            // Reflectively, because this plugin compiles without the compiler on its class
            // path -- and should keep doing so. Naming scala.Product here would make building
            // the plugin require a Flix release, which is a dependency on the thing it is
            // supposed to be able to inspect several versions of.
            try {
                if (hasMethod(node.getClass(), "productArity")) {
                    int arity = (Integer) call(node, "productArity");
                    Method element = node.getClass().getMethod("productElement", int.class);
                    for (int i = 0; i < arity; i++) visit(element.invoke(node, i), depth + 1);
                    return;
                }
                if (hasMethod(node.getClass(), "iterator")) {
                    Object it = call(node, "iterator");
                    if (!hasMethod(it.getClass(), "hasNext")) return;
                    while ((Boolean) call(it, "hasNext")) visit(call(it, "next"), depth + 1);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // One unreadable node costs its subtree, never the report.
            }
        }
    }

    /** Every value of a root map that belongs to this project rather than to the library. */
    private static List<Object> projectValues(Object root, String field, Path projectRoot)
            throws ReflectiveOperationException {
        Object map = call(root, field);
        Object iterable = hasMethod(map.getClass(), "values") ? call(map, "values") : map;
        Object iterator = call(iterable, "iterator");
        List<Object> out = new ArrayList<>();
        while ((Boolean) call(iterator, "hasNext")) {
            Object value = call(iterator, "next");
            if (isProjectValue(value, projectRoot)) out.add(value);
        }
        return out;
    }

    /** The project's own sources. Package-visible: {@link ResultCache} keys on this exact
     *  list, so computing it twice would be two chances to disagree about what a project is. */
    static List<Path> projectFiles(Path root) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        for (String directory : List.of("src", "test")) {
            Path base = root.resolve(directory);
            if (!Files.isDirectory(base)) continue;
            try (var paths = Files.walk(base)) {
                paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".flix"))
                    .sorted().forEach(files::add);
            }
        }
        if (files.isEmpty()) throw new Failure("no .flix files under " + root.resolve("src") + " or " + root.resolve("test"));
        return files;
    }

    private static Object bootstrap(ClassLoader loader, Path projectRoot) throws ReflectiveOperationException {
        Object none = loader.loadClass("scala.None$").getField("MODULE$").get(null);
        Object formatter = call(loader.loadClass("ca.uwaterloo.flix.util.Formatter$")
            .getField("MODULE$").get(null), "getDefault");
        Class<?> bootstrap = loader.loadClass("ca.uwaterloo.flix.api.Bootstrap");
        Object result = bootstrap.getMethod("bootstrap", Path.class, loader.loadClass("scala.Option"),
            loader.loadClass("ca.uwaterloo.flix.util.Formatter"), java.io.PrintStream.class)
            .invoke(null, projectRoot, none, formatter, System.err);
        return call(result, "unsafeGet");
    }


    private static boolean isProjectValue(Object value, Path projectRoot) throws ReflectiveOperationException {
        Object source = call(call(value, "loc"), "source");
        Object input = call(source, "input");
        if (!input.getClass().getName().endsWith("Input$RealFile")) return false;
        Path file = (Path) call(input, "realPath");
        return file.toAbsolutePath().normalize().startsWith(projectRoot);
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) if (method.getName().equals(name)) return true;
        return false;
    }

    private static Object optionGet(Object option) throws ReflectiveOperationException {
        return (Boolean) call(option, "isDefined") ? call(option, "get") : null;
    }

    private static Method find(Class<?> type, String name, int arity) {
        for (Method method : type.getMethods())
            if (method.getName().equals(name) && method.getParameterCount() == arity) return method;
        throw new Failure("compiler API is missing " + type.getName() + "." + name + "()");
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return find(target.getClass(), name, 0).invoke(target);
    }

    private static String cause(Exception e) {
        if (e instanceof InvocationTargetException wrapped && wrapped.getCause() != null)
            return wrapped.getCause().toString();
        return e.toString();
    }

    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Failure(String message) { super(message); }
    }
}
