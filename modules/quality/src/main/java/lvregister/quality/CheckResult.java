package lvregister.quality;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The result of one Dagster asset check, computed in Java and reported through Pipes. The
 * Dagster definitions declare the same names. A failed blocking check stops downstream assets.
 *
 * @param metadata counts and codes only, never person data; values are numbers, booleans,
 *                 strings, lists or maps
 */
public record CheckResult(String name, boolean passed, boolean blocking, Map<String, Object> metadata) {

    public CheckResult {
        metadata = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
