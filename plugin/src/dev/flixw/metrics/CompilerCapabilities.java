package dev.flixw.metrics;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
     * in the bytecode-derived contract is a type or member {@code Flix075Adapter} binds to at
     * compile time, so a compiler that fails it is one the engine could not have run against --
     * and it is told so in a sentence instead.
     *
     * <p>This class stays Java for exactly that reason. It has to load and answer on a machine
     * where {@code Flix075Adapter} would not link at all.
     */
    static CompilerCapabilities inspect(ClassLoader compiler, Path compilerJar) {
        if (!Files.isRegularFile(compilerJar))
            throw new Main.Usage("FLIXW_COMPILER_JAR is not a regular file: " + compilerJar);
        boolean flix = present(compiler, "ca.uwaterloo.flix.api.Flix");
        List<String> missing = new ArrayList<>();

        for (AdapterAbi.Reference reference : AdapterAbi.references())
            requireReference(compiler, missing, reference);

        return new CompilerCapabilities(flix, missing.isEmpty(),
            present(compiler, "ca.uwaterloo.flix.tools.Metrics$"), List.copyOf(missing));
    }

    private static void requireReference(ClassLoader loader, List<String> missing,
                                         AdapterAbi.Reference reference) {
        try {
            Class<?> owner = Class.forName(reference.owner().replace('/', '.'), false, loader);
            if (reference.kind() == AdapterAbi.Kind.CLASS) return;
            if (reference.kind() == AdapterAbi.Kind.FIELD) {
                for (Field field : owner.getFields())
                    if (field.getName().equals(reference.name())
                        && descriptor(field.getType()).equals(reference.descriptor())) return;
            } else if (reference.name().equals("<init>")) {
                for (Constructor<?> constructor : owner.getConstructors())
                    if (descriptor(constructor).equals(reference.descriptor())) return;
            } else {
                for (Method method : owner.getMethods())
                    if (method.getName().equals(reference.name())
                        && descriptor(method).equals(reference.descriptor())) return;
            }
            missing.add(reference.display());
        } catch (ClassNotFoundException | LinkageError e) {
            missing.add(reference.display());
        }
    }

    private static String descriptor(Method method) {
        return descriptor(method.getParameterTypes(), method.getReturnType());
    }

    private static String descriptor(Constructor<?> constructor) {
        return descriptor(constructor.getParameterTypes(), void.class);
    }

    private static String descriptor(Class<?>[] parameters, Class<?> result) {
        StringBuilder value = new StringBuilder("(");
        for (Class<?> parameter : parameters) value.append(descriptor(parameter));
        return value.append(')').append(descriptor(result)).toString();
    }

    private static String descriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new AssertionError("unknown primitive " + type);
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
