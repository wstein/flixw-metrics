package dev.flixw.metrics;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import dev.flixw.sdk.kit.KeyedStore;

/**
 * A content-addressed cache for one report.
 *
 * <p>This is the whole performance story of the plugin, and the numbers are why. Measured
 * against a two-definition project: the full run is ~3.9s, of which ~3.4s is the compiler
 * type-checking <em>its own standard library</em> before it can type two lines of yours. The
 * process architecture around it -- the JVM hops, the classpath assembly -- is under 10%
 * combined. So the only optimisation worth having is not doing the work twice, and a report
 * is a pure function of inputs that are all cheap to hash.
 *
 * <p>Entries are named {@code .measurements} because that is what they hold. They were
 * {@code .json} while the cache stored a rendered report; an extension that names the wrong
 * format is a small lie that costs somebody an afternoon the first time they open one.
 *
 * <p><b>What the key covers, and why each part.</b> The compiler's digest, because the
 * standard library is part of the compiler and changing it changes the typing of unchanged
     * source. The plugin's own version and artifact digest, because a new plugin may count
     * differently from the same root and local development builds share one version string.
     * Every project source path <em>with</em> its digest, sorted, because a deleted file
 * changes the report and a content-only hash would not notice. {@code flix.toml}, because it
 * selects dependencies that participate in typing.
 *
 * <p>The bridge's {@code sdkVersion} and the {@code adapterFamily} that linked are explicit
 * fields of the key, taken from {@code Adapters.Selection.cacheFields()} in the measuring phase
 * and derived the same way from the contracts in the outer phase. They are redundant with the
 * plugin artifact digest while the adapters ship in the plugin jar; they stay explicit so that a
 * change of either one cannot hit an entry written under another.
 *
 * <p><b>The directory is flixw's to name, not this plugin's to guess.</b>
 * {@code FLIXW_PLUGIN_CACHE} is where a plugin may keep derived data, and flixw collects it
 * when the plugin is removed or purged. An earlier draft picked its own path under the cache,
 * which worked and left bytes that nothing would ever clean up.
 */
final class ResultCache {
    private ResultCache() { }

    /**
     * Where flixw said to write, or null if this flixw is too old to say.
     *
     * <p>No fallback to a path of our own choosing. A guessed directory is one flixw does not
     * collect, so the honest behaviour against an older wrapper is to run uncached.
     */
    static Path directory(Main.Context context) {
        return KeyedStore.directory(context.kit());
    }

    /**
     * The cache key, or null when it cannot be computed.
     *
     * <p>Null disables caching for that run rather than failing it: a report that cannot be
     * cached is still a correct report, and an unreadable file here means the compiler is
     * about to produce a better diagnostic about it than this class could.
     */
    static String key(Main.Context context, List<Path> sources, String pluginVersion,
                      Map<String, String> fields) {
        try {
            Path plugin = Main.pluginArtifact();
            return key(context, sources, pluginVersion, plugin, fields);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    static String key(Main.Context context, List<Path> sources, String pluginVersion,
                      Path pluginArtifact, Map<String, String> fields) {
        String base = KeyedStore.key(context.kit(), sources, pluginVersion, pluginArtifact);
        if (base == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("kit-key " + base + '\n').getBytes(StandardCharsets.UTF_8));
            // Sorted, and each value length-prefixed: a value cannot pose as another field.
            for (Map.Entry<String, String> field : new TreeMap<>(fields).entrySet()) {
                digest.update(("field " + field.getKey() + ' ' + field.getValue().length() + ' '
                    + field.getValue() + '\n').getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    static String read(Path dir, String key) {
        return KeyedStore.read(dir, key);
    }

    static void write(Path dir, String key, String payload) {
        KeyedStore.write(dir, key, payload);
    }
}
