package dev.flixw.metrics;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares a current report with a previously emitted native JSON report. */
final class Baseline {
    private Baseline() { }

    record Snapshot(String id, String rule, String subject, String file, int line,
                    double actual, double limit, String unit, double overBy) {
        String where() { return file.isEmpty() ? "" : file + ":" + line; }

        String json() {
            return "{\"id\": " + SourceMetrics.Smell.quote(id)
                + ", \"rule\": " + SourceMetrics.Smell.quote(rule)
                + ", \"subject\": " + SourceMetrics.Smell.quote(subject)
                + ", \"file\": " + SourceMetrics.Smell.quote(file)
                + ", \"line\": " + line
                + ", \"actual\": " + number(actual)
                + ", \"limit\": " + number(limit)
                + ", \"unit\": " + SourceMetrics.Smell.quote(unit)
                + ", \"overBy\": " + number(overBy) + "}";
        }
    }

    record Change(Snapshot before, SourceMetrics.Smell after) { }

    /** A raw measurement change, independent of whether either value crosses a rule threshold. */
    record MeasurementDelta(String scope, String subject, String file, String metric,
                            BigDecimal before, BigDecimal after) {
        BigDecimal delta() { return after.subtract(before).stripTrailingZeros(); }

        String json() {
            return "{\"scope\": " + SourceMetrics.Smell.quote(scope)
                + ", \"subject\": " + SourceMetrics.Smell.quote(subject)
                + ", \"file\": " + SourceMetrics.Smell.quote(file)
                + ", \"metric\": " + SourceMetrics.Smell.quote(metric)
                + ", \"before\": " + number(before)
                + ", \"after\": " + number(after)
                + ", \"delta\": " + number(delta()) + "}";
        }
    }

    record Comparison(Path path, List<SourceMetrics.Smell> added, List<Change> worsened,
                      List<Snapshot> resolved, int retained,
                      List<MeasurementDelta> measurementDeltas) {
        Comparison(Path path, List<SourceMetrics.Smell> added, List<Change> worsened,
                   List<Snapshot> resolved, int retained) {
            this(path, added, worsened, resolved, retained, List.of());
        }

        Comparison {
            added = List.copyOf(added);
            worsened = List.copyOf(worsened);
            resolved = List.copyOf(resolved);
            measurementDeltas = List.copyOf(measurementDeltas);
        }

        boolean crosses(String level) {
            int threshold = RuleDefinitions.levelRank(level);
            return added.stream().anyMatch(s -> severity(s.rule()) >= threshold)
                || worsened.stream().anyMatch(c -> severity(c.after().rule()) >= threshold);
        }

        boolean isAdded(String id) {
            return added.stream().anyMatch(finding -> finding.id().equals(id));
        }

        boolean isWorsened(String id) {
            return worsened.stream().anyMatch(change -> change.after().id().equals(id));
        }

        String json() {
            StringBuilder out = new StringBuilder("{\"source\": ")
                .append(SourceMetrics.Smell.quote(path.toString()))
                .append(", \"newCount\": ").append(added.size())
                .append(", \"worsenedCount\": ").append(worsened.size())
                .append(", \"resolvedCount\": ").append(resolved.size())
                .append(", \"retainedCount\": ").append(retained)
                .append(", \"new\": [");
            for (int i = 0; i < added.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(added.get(i).json());
            }
            out.append("], \"worsened\": [");
            for (int i = 0; i < worsened.size(); i++) {
                if (i > 0) out.append(", ");
                Change change = worsened.get(i);
                out.append("{\"before\": ").append(change.before().json())
                    .append(", \"after\": ").append(change.after().json()).append('}');
            }
            out.append("], \"resolved\": [");
            for (int i = 0; i < resolved.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(resolved.get(i).json());
            }
            out.append("], \"measurementDeltas\": [");
            for (int i = 0; i < measurementDeltas.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(measurementDeltas.get(i).json());
            }
            return out.append("]}").toString();
        }

        String text() {
            StringBuilder out = new StringBuilder("\nbaseline: ")
                .append(added.size()).append(" new, ")
                .append(worsened.size()).append(" worsened, ")
                .append(resolved.size()).append(" resolved, ")
                .append(retained).append(" retained\n");
            for (SourceMetrics.Smell finding : added)
                out.append("  new       ").append(finding.text().stripLeading()).append('\n');
            for (Change change : worsened)
                out.append("  worsened  ").append(change.after().where()).append("  ")
                    .append(change.after().rule()).append("  (")
                    .append(number(change.before().overBy())).append("x -> ")
                    .append(number(change.after().overBy())).append("x)\n");
            for (Snapshot finding : resolved)
                out.append("  resolved  ")
                    .append(finding.where().isEmpty() ? finding.subject() : finding.where())
                    .append("  ").append(finding.rule())
                    .append("  [").append(finding.subject()).append("]\n");
            if (!measurementDeltas.isEmpty()) {
                out.append("  measurement changes: ").append(measurementDeltas.size()).append('\n');
                for (MeasurementDelta delta : measurementDeltas)
                    out.append("    ").append(delta.scope()).append("  ")
                        .append(delta.subject()).append("  ").append(delta.metric()).append("  ")
                        .append(number(delta.before())).append(" -> ")
                        .append(number(delta.after())).append('\n');
            }
            return out.toString();
        }

        String markdown() {
            StringBuilder out = new StringBuilder("## Baseline changes\n\n")
                .append("Compared with `").append(path).append("`: **")
                .append(added.size()).append(" new**, **")
                .append(worsened.size()).append(" worsened**, **")
                .append(resolved.size()).append(" resolved**, and ")
                .append(retained).append(" retained.\n\n")
                .append("Resolved: ").append(resolved.size()).append(".\n\n");
            if (!added.isEmpty()) {
                out.append("### New\n\n");
                for (SourceMetrics.Smell finding : added)
                    out.append("- `").append(finding.subject()).append("` — `")
                        .append(finding.rule()).append('`')
                        .append(finding.where().isEmpty() ? "" : " at `" + finding.where() + "`")
                        .append('\n');
                out.append('\n');
            }
            if (!worsened.isEmpty()) {
                out.append("### Worsened\n\n");
                for (Change change : worsened)
                    out.append("- `").append(change.after().subject()).append("` — `")
                        .append(change.after().rule()).append("` changed from ")
                        .append(number(change.before().overBy())).append("x to ")
                        .append(number(change.after().overBy())).append("x\n");
                out.append('\n');
            }
            if (!resolved.isEmpty()) {
                out.append("### Resolved\n\n");
                for (Snapshot finding : resolved)
                    out.append("- `").append(finding.subject()).append("` — `")
                        .append(finding.rule()).append('`')
                        .append(finding.where().isEmpty() ? "" : " at `" + finding.where() + "`")
                        .append('\n');
                out.append('\n');
            }
            if (!measurementDeltas.isEmpty()) {
                out.append("### Measurement changes\n\n")
                    .append("| Scope | Subject | Metric | Before | After | Delta |\n")
                    .append("| --- | --- | --- | ---: | ---: | ---: |\n");
                for (MeasurementDelta delta : measurementDeltas)
                    out.append("| ").append(delta.scope()).append(" | `")
                        .append(delta.subject()).append("` | `").append(delta.metric())
                        .append("` | ").append(number(delta.before())).append(" | ")
                        .append(number(delta.after())).append(" | ")
                        .append(number(delta.delta())).append(" |\n");
                out.append('\n');
            }
            return out.toString();
        }

        private static int severity(String rule) {
            return RuleDefinitions.levelRank(RuleDefinitions.byId(rule).level());
        }
    }

    static Comparison compare(Path path, Metrics.Report current, MetricsConfig config)
            throws IOException {
        Object parsed;
        try {
            parsed = new Json(Files.readString(path)).parse();
        } catch (Invalid e) {
            throw new Invalid(path + ": " + e.getMessage());
        }
        Map<String, Object> root = object(parsed, path, "report");
        int schema = integer(root.get("schemaVersion"), path, "schemaVersion");
        if (schema != Metrics.Report.schemaVersion() && schema != 24)
            throw invalid(path, "incompatible schema version " + schema
                + " (expected " + Metrics.Report.schemaVersion() + "; schema 24 is also"
                + " accepted for migration)");
        Object baselineConfig = required(root, "configuration", path);
        Object currentConfig = new Json(config.json()).parse();
        if (!baselineConfig.equals(currentConfig))
            throw invalid(path, "configuration differs from the current effective policy");

        Map<String, Object> currentRoot = object(
            new Json(current.render(Metrics.Format.JSON, null, config)).parse(), path,
            "current report");
        List<MeasurementDelta> measurementDeltas = measurements(root, currentRoot, path);

        List<Object> values = array(required(root, "smells", path), path, "smells");
        Map<String, Snapshot> previous = new LinkedHashMap<>();
        Map<String, String> semanticAliases = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            Snapshot finding = snapshot(object(values.get(i), path, "smells[" + i + "]"), path);
            if (previous.putIfAbsent(finding.id(), finding) != null)
                throw invalid(path, "duplicate finding id " + finding.id());
            if (!finding.subject().equals(finding.where())) {
                String semantic = new SourceMetrics.Smell(finding.rule(), finding.subject(),
                    finding.file(), finding.line(), finding.actual(), finding.limit(), "",
                    finding.unit()).id();
                semanticAliases.putIfAbsent(semantic, finding.id());
            }
        }

        List<SourceMetrics.Smell> added = new ArrayList<>();
        List<Change> worsened = new ArrayList<>();
        int retained = 0;
        for (SourceMetrics.Smell finding : current.smells()) {
            Snapshot before = previous.remove(finding.id());
            if (before == null) {
                String oldId = semanticAliases.get(finding.id());
                if (oldId != null) before = previous.remove(oldId);
            }
            // Baselines emitted before semantic fingerprints included the physical line.
            // Accept that identity once so upgrading the analyzer does not turn every existing
            // finding into a simultaneous resolution and addition.
            if (before == null) before = previous.remove(finding.legacyId());
            if (before == null) {
                added.add(finding);
            } else if (Double.compare(reportedOverBy(finding), before.overBy()) > 0) {
                worsened.add(new Change(before, finding));
            } else {
                retained++;
            }
        }
        return new Comparison(path, added, worsened, new ArrayList<>(previous.values()), retained,
            measurementDeltas);
    }

    private static List<MeasurementDelta> measurements(Map<String, Object> before,
                                                        Map<String, Object> after, Path path) {
        List<MeasurementDelta> result = new ArrayList<>();
        numericDeltas(result, "summary", "project", "",
            object(required(before, "summary", path), path, "summary"),
            object(required(after, "summary", path), path, "current summary"), Set.of());
        entityDeltas(result, "definition", "definitions", before, after, path,
            Set.of("line", "maxLineTokensLine"));
        entityDeltas(result, "module", "modules", before, after, path, Set.of());
        return List.copyOf(result);
    }

    private static void entityDeltas(List<MeasurementDelta> result, String scope, String field,
                                     Map<String, Object> before, Map<String, Object> after,
                                     Path path, Set<String> excluded) {
        Map<String, Map<String, Object>> previous = entities(before, field, path);
        for (Object item : array(required(after, field, path), path, "current " + field)) {
            Map<String, Object> current = object(item, path, "current " + scope);
            String subject = string(required(current, "name", path), path, scope + " name");
            String file = scope.equals("definition")
                ? string(required(current, "file", path), path, scope + " file") : "";
            Map<String, Object> old = previous.get(subject + '\0' + file);
            if (old != null) numericDeltas(result, scope, subject, file, old, current, excluded);
        }
    }

    private static Map<String, Map<String, Object>> entities(Map<String, Object> root,
                                                              String field, Path path) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object item : array(required(root, field, path), path, field)) {
            Map<String, Object> entity = object(item, path, field + " entry");
            String subject = string(required(entity, "name", path), path, field + " name");
            String file = field.equals("definitions")
                ? string(required(entity, "file", path), path, field + " file") : "";
            result.put(subject + '\0' + file, entity);
        }
        return result;
    }

    private static void numericDeltas(List<MeasurementDelta> result, String scope,
                                      String subject, String file, Map<String, Object> before,
                                      Map<String, Object> after, Set<String> excluded) {
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            if (excluded.contains(entry.getKey()) || !(entry.getValue() instanceof BigDecimal old)
                    || !(after.get(entry.getKey()) instanceof BigDecimal current)
                    || old.compareTo(current) == 0) continue;
            result.add(new MeasurementDelta(scope, subject, file, entry.getKey(), old, current));
        }
    }

    private static Snapshot snapshot(Map<String, Object> value, Path path) {
        String id = string(required(value, "id", path), path, "finding id");
        String rule = string(required(value, "rule", path), path, "finding rule");
        try {
            RuleDefinitions.byId(rule);
        } catch (IllegalArgumentException e) {
            throw invalid(path, "baseline contains unknown rule " + rule);
        }
        return new Snapshot(id, rule,
            string(required(value, "subject", path), path, "finding subject"),
            string(required(value, "file", path), path, "finding file"),
            integer(required(value, "line", path), path, "finding line"),
            decimal(required(value, "actual", path), path, "finding actual"),
            decimal(required(value, "limit", path), path, "finding limit"),
            string(required(value, "unit", path), path, "finding unit"),
            decimal(required(value, "overBy", path), path, "finding overBy"));
    }

    private static Object required(Map<String, Object> object, String key, Path path) {
        if (!object.containsKey(key)) throw invalid(path, "missing " + key);
        return object.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, Path path, String name) {
        if (!(value instanceof Map<?, ?>)) throw invalid(path, name + " must be an object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, Path path, String name) {
        if (!(value instanceof List<?>)) throw invalid(path, name + " must be an array");
        return (List<Object>) value;
    }

    private static String string(Object value, Path path, String name) {
        if (!(value instanceof String text)) throw invalid(path, name + " must be a string");
        return text;
    }

    private static int integer(Object value, Path path, String name) {
        if (!(value instanceof BigDecimal number)) throw invalid(path, name + " must be a number");
        try {
            return number.intValueExact();
        } catch (ArithmeticException e) {
            throw invalid(path, name + " must be an integer");
        }
    }

    private static double decimal(Object value, Path path, String name) {
        if (!(value instanceof BigDecimal number)) throw invalid(path, name + " must be a number");
        double result = number.doubleValue();
        if (!Double.isFinite(result)) throw invalid(path, name + " must be finite");
        return result;
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** Compare the precision promised by report JSON, not hidden floating-point digits. */
    private static double reportedOverBy(SourceMetrics.Smell finding) {
        return Double.parseDouble(String.format(java.util.Locale.ROOT, "%.2f", finding.overBy()));
    }

    private static Invalid invalid(Path path, String message) {
        return new Invalid(path + ": " + message);
    }

    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Invalid(String message) { super(message); }
    }

    /** Small strict JSON reader, kept dependency-free for the outer JVM's isolated class path. */
    private static final class Json {
        private static final int MAX_NESTING = 128;
        private final String input;
        private int at;

        Json(String input) { this.input = input; }

        Object parse() {
            Object value = value(0);
            space();
            if (at != input.length()) throw error("unexpected trailing content");
            return value;
        }

        private Object value(int depth) {
            space();
            if (at >= input.length()) throw error("unexpected end of JSON");
            if (depth > MAX_NESTING) throw error("JSON nesting exceeds " + MAX_NESTING);
            return switch (input.charAt(at)) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            at++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            while (true) {
                space();
                if (at >= input.length() || input.charAt(at) != '"')
                    throw error("object key must be a string");
                String key = string();
                space();
                expect(':');
                if (result.containsKey(key)) throw error("duplicate object key " + key);
                result.put(key, value(depth + 1));
                space();
                if (take('}')) return result;
                expect(',');
            }
        }

        private List<Object> array(int depth) {
            at++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            while (true) {
                result.add(value(depth + 1));
                space();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (at < input.length()) {
                char c = input.charAt(at++);
                if (c == '"') return result.toString();
                if (c < 0x20) throw error("control character in string");
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (at >= input.length()) throw error("unfinished string escape");
                char escaped = input.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicode());
                    default -> throw error("invalid string escape");
                }
            }
            throw error("unterminated string");
        }

        private char unicode() {
            if (at + 4 > input.length()) throw error("unfinished unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(input.charAt(at++), 16);
                if (digit < 0) throw error("invalid unicode escape");
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private BigDecimal number() {
            int start = at;
            if (take('-') && at >= input.length()) throw error("invalid number");
            if (take('0')) {
                if (at < input.length() && Character.isDigit(input.charAt(at)))
                    throw error("leading zero in number");
            } else {
                digits();
            }
            if (take('.')) digits();
            if (take('e') || take('E')) {
                take('+');
                take('-');
                digits();
            }
            try {
                return new BigDecimal(input.substring(start, at)).stripTrailingZeros();
            } catch (NumberFormatException e) {
                throw error("invalid number");
            }
        }

        private void digits() {
            int start = at;
            while (at < input.length() && Character.isDigit(input.charAt(at))) at++;
            if (start == at) throw error("expected digit");
        }

        private Object literal(String text, Object value) {
            if (!input.startsWith(text, at)) throw error("invalid value");
            at += text.length();
            return value;
        }

        private void space() {
            while (at < input.length() && Character.isWhitespace(input.charAt(at))) at++;
        }

        private boolean take(char wanted) {
            if (at < input.length() && input.charAt(at) == wanted) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(char wanted) {
            if (!take(wanted)) throw error("expected '" + wanted + "'");
        }

        private Invalid error(String message) {
            return new Invalid(message + " at character " + at);
        }
    }
}
