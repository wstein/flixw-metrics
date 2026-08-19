package dev.flixw.metrics.sdk;

import java.util.List;

/**
 * Finds the adapter that links against the compiler on this class path.
 *
 * <p>Selection is by <em>linkage</em>, not by version string. Instantiating an adapter forces
 * the JVM to resolve the compiler types it binds to, so an adapter written for an AST this
 * compiler does not have fails here, in a controlled place, instead of part-way through a
 * measurement. A fork reporting an unfamiliar version may still link; a compiler reporting a
 * familiar one may not. The JVM knows, and it is cheaper to ask it than to maintain a table of
 * which versions are secretly compatible.
 *
 * <p>Loaded by name so that nothing outside this method mentions an adapter class. The phase
 * that runs without a compiler on its class path can hold a reference to {@link CompilerModel}
 * all day; it never loads anything that would need Scala or Flix to resolve.
 */
public final class Adapters {
    private Adapters() { }

    /**
     * Newest first, since a newer adapter is the better answer when two both link.
     *
     * <p>This list is the whole registry. Supporting another Flix generation is a class and a
     * line here -- and explicitly not an edit to the report, the smells, the formats or the
     * cache, which is the property the SDK exists to buy.
     */
    private static final List<String> KNOWN =
        List.of("dev.flixw.metrics.flix075.Flix075Adapter");

    /** The first adapter that links, or null when none does. */
    public static CompilerModel resolve() {
        for (String name : KNOWN) {
            try {
                Class<?> type = Class.forName(name);
                Object instance = type.getDeclaredConstructor().newInstance();
                if (instance instanceof CompilerModel model) return model;
            } catch (ReflectiveOperationException | LinkageError e) {
                // Exactly the case this loop is for: the adapter cannot bind to this compiler.
                // LinkageError is the one that matters and is not an Exception, which is why it
                // is named -- NoClassDefFoundError from a missing AST type arrives as an Error.
            }
        }
        return null;
    }

    /** What this build could support, for a diagnostic that names something actionable. */
    public static List<String> known() {
        return KNOWN;
    }
}
