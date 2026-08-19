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

    record Report(int files, int definitions, int traits, int instances, int enums, int structs,
                  int effects, int typeAliases) {
        String render(Format format) {
            if (format == Format.JSON) return "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"files\": " + files + ",\n"
                + "  \"definitions\": " + definitions + ",\n"
                + "  \"traits\": " + traits + ",\n"
                + "  \"instances\": " + instances + ",\n"
                + "  \"enums\": " + enums + ",\n"
                + "  \"structs\": " + structs + ",\n"
                + "  \"effects\": " + effects + ",\n"
                + "  \"typeAliases\": " + typeAliases + "\n}\n";
            return "files: " + files + "\ndefinitions: " + definitions + "\ntraits: " + traits
                + "\ninstances: " + instances + "\nenums: " + enums + "\nstructs: " + structs
                + "\neffects: " + effects + "\ntype aliases: " + typeAliases + "\n";
        }

        /**
         * Reads back what {@link #render} wrote, or null if it is not that.
         *
         * <p>This parses one fixed shape this class emitted itself, which is why scanning for
         * {@code "name": <integer>} is enough and a JSON library would be a dependency bought
         * for nothing. Null on anything unexpected -- a truncated file, a hand-edited entry,
         * a {@code schemaVersion} this build does not know -- and the caller recomputes. A
         * cache entry is an optimisation, so the only wrong answer here is a confident one.
         */
        static Report fromJson(String json) {
            Integer schema = field(json, "schemaVersion");
            if (schema == null || schema != 1) return null;
            Integer f = field(json, "files"), d = field(json, "definitions");
            Integer t = field(json, "traits"), i = field(json, "instances");
            Integer e = field(json, "enums"), st = field(json, "structs");
            Integer ef = field(json, "effects"), ta = field(json, "typeAliases");
            if (f == null || d == null || t == null || i == null
                || e == null || st == null || ef == null || ta == null) return null;
            return new Report(f, d, t, i, e, st, ef, ta);
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

    /**
     * Measures through the system class loader, which is why this runs in its own JVM.
     *
     * <p><b>An isolated {@code URLClassLoader} does not work here, and it is worth recording
     * why so nobody tries it again.</b> The Flix standard library imports Java classes that
     * live inside {@code flix.jar} itself -- {@code dev.flix.runtime.Global} among them -- and
     * the compiler's resolver looks those up through the <em>application</em> class path, not
     * through the loader that defined the compiler. Load Flix from a child loader and its own
     * standard library fails to resolve with four {@code E1803 Undefined Java class} errors
     * before a single line of the project is typed. Setting the thread context class loader
     * does not help either; both were measured, not assumed.
     *
     * <p>So {@code flix.jar} must be flat on {@code -cp}, and the only way to have that while
     * the plugin is launched as its own JAR is a second JVM. That costs a measured 270ms and
     * it is not optional. The consequence to live with is that the compiler's bundled ASM,
     * JLine, gson and json4s are visible to this plugin: any dependency added here must be
     * shaded, because on a flat class path the winner is decided by ordering.
     */
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
            if (root == null) throw new Failure("the compiler produced no typed root; fix compilation errors first");
            return new Report(sources.size(), countProject(root, "defs", context.projectRoot()),
                countProject(root, "traits", context.projectRoot()), countProject(root, "instances", context.projectRoot()),
                countProject(root, "enums", context.projectRoot()), countProject(root, "structs", context.projectRoot()),
                countProject(root, "effects", context.projectRoot()), countProject(root, "typeAliases", context.projectRoot()));
        } catch (ReflectiveOperationException e) {
            throw new Failure("cannot read the pinned compiler's typed root: " + cause(e));
        }
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

    private static int countProject(Object root, String field, Path projectRoot) throws ReflectiveOperationException {
        Object map = call(root, field);
        Object iterable = hasMethod(map.getClass(), "values") ? call(map, "values") : map;
        Object iterator = call(iterable, "iterator");
        int count = 0;
        while ((Boolean) call(iterator, "hasNext")) {
            if (isProjectValue(call(iterator, "next"), projectRoot)) count++;
        }
        return count;
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
