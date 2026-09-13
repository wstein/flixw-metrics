package dev.flixw.metrics;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;

/** Strict, repository-local policy over measurements; absent means the documented defaults. */
final class MetricsConfig {
    static final String FILE = ".flixw-metrics.properties";

    private final Map<String, Double> limits;
    private final Set<String> disabled;
    private final List<Suppression> suppressions;
    private final List<Exclusion> exclusions;
    private final LocalDate today;

    private MetricsConfig(Map<String, Double> limits, Set<String> disabled,
                          List<Suppression> suppressions, List<Exclusion> exclusions,
                          LocalDate today) {
        this.limits = Map.copyOf(limits);
        this.disabled = Set.copyOf(disabled);
        this.suppressions = List.copyOf(suppressions);
        this.exclusions = List.copyOf(exclusions);
        this.today = today;
    }

    static MetricsConfig defaults() {
        return new MetricsConfig(Map.of(), Set.of(), List.of(), List.of(), LocalDate.now());
    }

    static MetricsConfig read(Path root) {
        return read(root, LocalDate.now());
    }

    static MetricsConfig read(Path root, LocalDate today) {
        Path path = root.resolve(FILE);
        if (!Files.isRegularFile(path))
            return new MetricsConfig(Map.of(), Set.of(), List.of(), List.of(), today);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            throw invalid(path, "cannot read: " + e.getMessage());
        }

        Map<String, Double> limits = new HashMap<>();
        Set<String> disabled = new HashSet<>();
        Map<String, SuppressionBuilder> builders = new HashMap<>();
        Map<String, ExclusionBuilder> exclusionBuilders = new HashMap<>();
        for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
            String value = properties.getProperty(key).trim();
            if (key.startsWith("rules.")) {
                readRule(path, key, value, limits, disabled);
            } else if (key.startsWith("suppressions.")) {
                readSuppression(path, key, value, builders);
            } else if (key.startsWith("exclusions.")) {
                readExclusion(path, key, value, exclusionBuilders);
            } else {
                throw invalid(path, "unknown key " + key);
            }
        }
        List<Suppression> suppressions = builders.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getValue().build(path, entry.getKey())).toList();
        List<Exclusion> exclusions = exclusionBuilders.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getValue().build(path, entry.getKey())).toList();
        return new MetricsConfig(limits, disabled, suppressions, exclusions, today);
    }

    boolean enabled(RuleDefinitions.Rule rule) {
        return !disabled.contains(rule.id());
    }

    double limit(RuleDefinitions.Rule rule) {
        return limits.getOrDefault(rule.id(), rule.defaultLimit());
    }

    boolean isSuppressed(SourceMetrics.Smell smell) {
        return suppressions.stream().anyMatch(s -> s.active(today) && s.matches(smell));
    }

    boolean isExcluded(String file) {
        return exclusion(file) != null;
    }

    ExcludedSource excludedSource(String file) {
        Exclusion found = exclusion(file);
        return found == null ? null : new ExcludedSource(file.replace('\\', '/'), found.reason());
    }

    private Exclusion exclusion(String file) {
        if (file == null || file.isEmpty()) return null;
        String portable = file.replace('\\', '/');
        return exclusions.stream().filter(e -> e.file().matcher(portable).matches())
            .findFirst().orElse(null);
    }

    String policySummary() {
        List<String> parts = RuleDefinitions.all().stream().map(rule -> enabled(rule)
            ? rule.categorical() ? rule.id() + "=on" : rule.id() + "=" + number(limit(rule))
            : rule.id() + "=off").toList();
        long active = suppressions.stream().filter(s -> s.active(today)).count();
        // Exclusions already earn a visible count here; a suppression removes a finding just
        // as completely and had none. A reader seeing "0 findings" next to a rule's limit
        // could not tell "nothing crossed it" from "something did, and it is hidden" without
        // this -- the native JSON's "activeSuppressions" already carries the same fact.
        return String.join(", ", parts) + "; exclusions=" + exclusions.size()
            + "; activeSuppressions=" + active;
    }

    String json() {
        StringBuilder out = new StringBuilder("{\"source\": ")
            .append(SourceMetrics.Smell.quote(FILE)).append(", \"policyDigest\": ")
            .append(SourceMetrics.Smell.quote(policyDigest())).append(", \"rules\": {");
        List<RuleDefinitions.Rule> rules = RuleDefinitions.all();
        for (int i = 0; i < rules.size(); i++) {
            RuleDefinitions.Rule rule = rules.get(i);
            if (i > 0) out.append(", ");
            out.append(SourceMetrics.Smell.quote(rule.id())).append(": {\"enabled\": ")
                .append(enabled(rule));
            if (!rule.categorical()) out.append(", \"limit\": ").append(number(limit(rule)));
            if (rule.minimumCodeLines() > 0)
                out.append(", \"minimumCodeLines\": ").append(rule.minimumCodeLines());
            out.append('}');
        }
        long active = suppressions.stream().filter(s -> s.active(today)).count();
        out.append("}, \"activeSuppressions\": ").append(active).append(", \"exclusions\": [");
        for (int i = 0; i < exclusions.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(exclusions.get(i).json());
        }
        return out.append("]}").toString();
    }

    /** Identity of every effective rule and suppression, used to reject unlike baselines. */
    private String policyDigest() {
        StringBuilder policy = new StringBuilder(policySummary());
        suppressions.stream().filter(s -> s.active(today)).forEach(s -> policy.append('\0')
            .append(s.rule()).append('\0')
            .append(s.file() == null ? "" : s.file().pattern()).append('\0')
            .append(s.subject() == null ? "" : s.subject().pattern()));
        exclusions.forEach(e -> policy.append('\0').append("exclude").append('\0')
            .append(e.fileGlob()).append('\0').append(e.reason()));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(policy.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes)
                out.append(Character.forDigit((value >> 4) & 0xf, 16))
                    .append(Character.forDigit(value & 0xf, 16));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("the JVM has no SHA-256", e);
        }
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
            : String.format(Locale.ROOT, "%.3f", value).replaceFirst("0+$", "").replaceFirst("\\.$", "");
    }

    private static void readRule(Path path, String key, String value, Map<String, Double> limits,
                                 Set<String> disabled) {
        String rest = key.substring("rules.".length());
        int dot = rest.lastIndexOf('.');
        if (dot <= 0) throw invalid(path, "invalid rule key " + key);
        String id = rest.substring(0, dot);
        String field = rest.substring(dot + 1);
        RuleDefinitions.Rule rule;
        try {
            rule = RuleDefinitions.byId(id);
        } catch (IllegalArgumentException e) {
            throw invalid(path, "unknown rule " + id);
        }
        switch (field) {
            case "enabled" -> {
                if (!value.equals("true") && !value.equals("false"))
                    throw invalid(path, key + " must be true or false");
                if (value.equals("false")) disabled.add(id); else disabled.remove(id);
            }
            case "limit" -> {
                if (rule.categorical()) throw invalid(path, key + " is categorical and has no limit");
                try {
                    double limit = Double.parseDouble(value);
                    if (!Double.isFinite(limit) || limit <= 0) throw new NumberFormatException();
                    limits.put(id, limit);
                } catch (NumberFormatException e) {
                    throw invalid(path, key + " must be a positive finite number");
                }
            }
            default -> throw invalid(path, "unknown rule field " + key);
        }
    }

    private static void readSuppression(Path path, String key, String value,
                                        Map<String, SuppressionBuilder> builders) {
        String rest = key.substring("suppressions.".length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1)
            throw invalid(path, "invalid suppression key " + key);
        String label = rest.substring(0, dot);
        String field = rest.substring(dot + 1);
        SuppressionBuilder builder = builders.computeIfAbsent(label, ignored -> new SuppressionBuilder());
        switch (field) {
            case "rule" -> {
                if (!value.equals("*")) {
                    try { RuleDefinitions.byId(value); }
                    catch (IllegalArgumentException e) { throw invalid(path, "unknown rule " + value); }
                }
                builder.rule = value;
            }
            case "file" -> builder.file = value;
            case "subject" -> builder.subject = value;
            case "reason" -> builder.reason = value;
            case "until" -> {
                try { builder.until = LocalDate.parse(value); }
                catch (DateTimeParseException e) { throw invalid(path, key + " must be YYYY-MM-DD"); }
            }
            default -> throw invalid(path, "unknown suppression field " + key);
        }
    }

    private static void readExclusion(Path path, String key, String value,
                                      Map<String, ExclusionBuilder> builders) {
        String rest = key.substring("exclusions.".length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1)
            throw invalid(path, "invalid exclusion key " + key);
        String label = rest.substring(0, dot);
        String field = rest.substring(dot + 1);
        ExclusionBuilder builder = builders.computeIfAbsent(label,
            ignored -> new ExclusionBuilder());
        switch (field) {
            case "file" -> builder.file = value;
            case "reason" -> builder.reason = value;
            default -> throw invalid(path, "unknown exclusion field " + key);
        }
    }

    private static Invalid invalid(Path path, String message) {
        return new Invalid(path + ": " + message);
    }

    private record Suppression(String rule, Pattern file, Pattern subject, String reason,
                               LocalDate until) {
        boolean active(LocalDate date) { return until == null || !date.isAfter(until); }

        boolean matches(SourceMetrics.Smell smell) {
            return (rule.equals("*") || rule.equals(smell.rule()))
                && (file == null || file.matcher(smell.file().replace('\\', '/')).matches())
                && (subject == null || subject.matcher(smell.subject()).matches());
        }
    }

    record ExcludedSource(String file, String reason) {
        String json() {
            return "{\"file\": " + SourceMetrics.Smell.quote(file)
                + ", \"reason\": " + SourceMetrics.Smell.quote(reason) + "}";
        }
    }

    private record Exclusion(String fileGlob, Pattern file, String reason) {
        String json() {
            return "{\"file\": " + SourceMetrics.Smell.quote(fileGlob)
                + ", \"reason\": " + SourceMetrics.Smell.quote(reason) + "}";
        }
    }

    private static final class ExclusionBuilder {
        String file;
        String reason;

        Exclusion build(Path path, String label) {
            if (file == null || file.isBlank())
                throw invalid(path, "exclusion " + label + " needs file");
            if (reason == null || reason.isBlank())
                throw invalid(path, "exclusion " + label + " needs reason");
            return new Exclusion(file.replace('\\', '/'), glob(file), reason);
        }
    }

    private static final class SuppressionBuilder {
        String rule;
        String file;
        String subject;
        String reason;
        LocalDate until;

        Suppression build(Path path, String label) {
            if (rule == null || rule.isBlank()) throw invalid(path, "suppression " + label + " needs rule");
            if ((file == null || file.isBlank()) && (subject == null || subject.isBlank()))
                throw invalid(path, "suppression " + label + " needs file or subject");
            if (reason == null || reason.isBlank())
                throw invalid(path, "suppression " + label + " needs reason");
            return new Suppression(rule, file == null ? null : glob(file),
                subject == null ? null : glob(subject), reason, until);
        }
    }

    /** Portable glob semantics shared by persisted exclusions and CLI presentation filters. */
    static Pattern glob(String value) {
        String portable = value.replace('\\', '/');
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < portable.length(); i++) {
            char c = portable.charAt(i);
            if (c == '*') {
                boolean recursive = i + 1 < portable.length() && portable.charAt(i + 1) == '*';
                if (recursive) i++;
                regex.append(recursive ? ".*" : "[^/]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }

    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Invalid(String message) { super(message); }
    }
}
