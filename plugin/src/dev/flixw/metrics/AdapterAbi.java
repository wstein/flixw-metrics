package dev.flixw.metrics;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The exact Flix ABI referenced by the compiled, version-specific adapter. */
final class AdapterAbi {
    private static final String ADAPTER = "dev/flixw/metrics/flix0753/Flix0753Adapter";
    private static final String FLIX = "ca/uwaterloo/flix/";
    private static final List<Reference> REFERENCES = readAdapterTree();

    private AdapterAbi() { }

    enum Kind { CLASS, FIELD, METHOD }

    record Reference(Kind kind, String owner, String name, String descriptor) {
        String display() {
            String type = owner.replace('/', '.');
            return kind == Kind.CLASS ? "class " + type : type + "." + name + ":" + descriptor;
        }
    }

    static List<Reference> references() {
        return REFERENCES;
    }

    /**
     * Scala may move a closure into a generated nested class. Follow those class references too,
     * so changing that compiler detail cannot move a Flix call outside the capability contract.
     */
    private static List<Reference> readAdapterTree() {
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        Set<Reference> found = new LinkedHashSet<>();
        pending.add(ADAPTER);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!visited.add(name)) continue;
            Parsed parsed = read(name);
            for (String type : parsed.classes()) {
                if (type.startsWith(ADAPTER + "$")) pending.add(type);
                if (type.startsWith(FLIX)) found.add(new Reference(Kind.CLASS, type, "", ""));
            }
            for (Reference reference : parsed.members())
                if (reference.owner().startsWith(FLIX)) found.add(reference);
        }
        return found.stream().sorted(Comparator.comparing(Reference::display)).toList();
    }

    private static Parsed read(String internalName) {
        String resource = "/" + internalName + ".class";
        try (InputStream raw = AdapterAbi.class.getResourceAsStream(resource)) {
            if (raw == null) throw new IllegalStateException("missing adapter class resource " + resource);
            return parse(raw);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read adapter class resource " + resource, e);
        }
    }

    private static Parsed parse(InputStream input) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(input))) {
            if (in.readInt() != 0xcafebabe) throw new IOException("invalid class-file magic");
            in.readUnsignedShort();
            in.readUnsignedShort();
            Object[] pool = new Object[in.readUnsignedShort()];
            for (int i = 1; i < pool.length; i++) {
                int tag = in.readUnsignedByte();
                pool[i] = switch (tag) {
                    case 1 -> new Utf8(in.readUTF());
                    case 3, 4 -> { in.readInt(); yield null; }
                    case 5, 6 -> { in.readLong(); i++; yield null; }
                    case 7 -> new ClassInfo(in.readUnsignedShort());
                    case 8, 16, 19, 20 -> { in.readUnsignedShort(); yield null; }
                    case 9 -> new Member(Kind.FIELD, in.readUnsignedShort(), in.readUnsignedShort());
                    case 10, 11 -> new Member(Kind.METHOD, in.readUnsignedShort(), in.readUnsignedShort());
                    case 12 -> new NameAndType(in.readUnsignedShort(), in.readUnsignedShort());
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); yield null; }
                    case 17, 18 -> { in.readUnsignedShort(); in.readUnsignedShort(); yield null; }
                    default -> throw new IOException("unsupported constant-pool tag " + tag);
                };
            }
            Set<String> classes = new LinkedHashSet<>();
            List<Reference> members = new ArrayList<>();
            for (Object entry : pool) {
                if (entry instanceof ClassInfo type) classes.add(utf8(pool, type.name()));
                if (entry instanceof Member member) {
                    String owner = utf8(pool, classInfo(pool, member.owner()).name());
                    NameAndType value = nameAndType(pool, member.nameAndType());
                    members.add(new Reference(member.kind(), owner, utf8(pool, value.name()),
                        utf8(pool, value.descriptor())));
                }
            }
            return new Parsed(classes, members);
        }
    }

    private static String utf8(Object[] pool, int index) throws IOException {
        if (pool[index] instanceof Utf8 value) return value.value();
        throw new IOException("constant-pool entry " + index + " is not UTF-8");
    }

    private static ClassInfo classInfo(Object[] pool, int index) throws IOException {
        if (pool[index] instanceof ClassInfo value) return value;
        throw new IOException("constant-pool entry " + index + " is not a class");
    }

    private static NameAndType nameAndType(Object[] pool, int index) throws IOException {
        if (pool[index] instanceof NameAndType value) return value;
        throw new IOException("constant-pool entry " + index + " is not a name and type");
    }

    private record Parsed(Set<String> classes, List<Reference> members) { }
    private record Utf8(String value) { }
    private record ClassInfo(int name) { }
    private record NameAndType(int name, int descriptor) { }
    private record Member(Kind kind, int owner, int nameAndType) { }
}
