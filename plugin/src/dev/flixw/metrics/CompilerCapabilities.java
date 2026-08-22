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
     * The gate must name what the engine actually links against.
     *
     * <p>Since the engine moved to Scala the gate matters more, not less. A reflective engine
     * limped along and produced something; a linked one throws {@code NoSuchMethodError} from
     * inside the JVM's verifier, with a message written for whoever wrote the JVM. Everything
     * below is a type or member {@code MetricsEngine} binds to at compile time, so a compiler
     * that fails this list is one the engine could not have run against -- and it is told so
     * in a sentence instead.
     *
     * <p>This class stays Java for exactly that reason. It has to load and answer on a machine
     * where {@code MetricsEngine} would not link at all.
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
        // The AST the engine pattern-matches on. These are what actually break when a compiler
        // reorganises its internals, and none of them were checked while the engine reflected
        // by name -- a missing type simply became a count of zero.
        requireClass(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$Root");
        requireClass(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$Def");
        requireClass(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$Expr$IfThenElse");
        requireClass(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$Expr$LocalDef");
        requireClass(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$MatchRule");
        requireClass(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$ExtMatchRule");
        requireClass(compiler, missing, "ca.uwaterloo.flix.language.ast.shared.Input$RealFile");
        requireMethod(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$Root", "defs", 0);
        requireMethod(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$Def", "exp", 0);
        requireMethod(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$Def", "sym", 0);
        // Return types, not just names. An accessor can keep its name and arity and change
        // what it hands back, which the loop above cannot see and the JVM resolves at the
        // call site: a fork whose Spec.fparams returns Nel rather than List passed every
        // check here and then died with NoSuchMethodError mid-measurement.
        // Measured against plugin/lib/flix.jar, the release this engine is compiled against,
        // rather than guessed: stock 0.75.3 hands back Nel here and a fork that kept List
        // passes every name-and-arity check before dying at the call site.
        requireReturn(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$Spec",
                      "fparams", "ca.uwaterloo.flix.util.collection.Nel");
        requireReturn(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$Root",
                      "defs", "scala.collection.immutable.Map");
        requireReturn(compiler, missing, "ca.uwaterloo.flix.language.ast.TypedAst$Def",
                      "exp", "ca.uwaterloo.flix.language.ast.TypedAst$Expr");

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

    /**
     * A method that must exist *and* hand back what the engine expects.
     *
     * <p>Reported as `owner.name: want, got` rather than as merely missing, because the
     * difference matters to whoever reads it: a name that is gone means a compiler
     * reorganised, and a return type that moved means this build was compiled against a
     * different one.
     */
    private static void requireReturn(ClassLoader loader, List<String> missing, String owner,
                                      String name, String wantReturn) {
        try {
            Class<?> type = Class.forName(owner, false, loader);
            for (var method : type.getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) continue;
                String got = method.getReturnType().getName();
                if (got.equals(wantReturn)) return;
                missing.add(owner + "." + name + ": expected " + wantReturn + ", found " + got);
                return;
            }
            missing.add(owner + "." + name + "/0");
        } catch (ClassNotFoundException e) {
            missing.add(owner);
        }
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
