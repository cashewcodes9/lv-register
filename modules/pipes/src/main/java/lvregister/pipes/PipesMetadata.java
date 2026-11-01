package lvregister.pipes;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata values in the Pipes form {@code {"raw_value": v, "type": t}}. The type follows the
 * Java value: integers {@code int}, decimals {@code float}, booleans {@code bool}, strings
 * {@code text}, maps and lists {@code json}, {@link Url} {@code url}.
 */
public final class PipesMetadata {

    /** A link shown as such in the Dagster UI, for example {@code s3://lv-lake/...}. */
    public record Url(String value) {}

    private PipesMetadata() {}

    public static Map<String, Object> encode(Map<String, Object> metadata) {
        Map<String, Object> out = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            Map<String, Object> typed = new LinkedHashMap<>();
            typed.put("raw_value", raw(value));
            typed.put("type", type(value));
            out.put(key, typed);
        });
        return out;
    }

    private static Object raw(Object value) {
        return value instanceof Url url ? url.value() : value;
    }

    private static String type(Object value) {
        return switch (value) {
            case Integer _, Long _, Short _ -> "int";
            case Double _, Float _ -> "float";
            case Boolean _ -> "bool";
            case String _ -> "text";
            case Url _ -> "url";
            case Map<?, ?> _, Collection<?> _ -> "json";
            default -> throw new IllegalArgumentException("unsupported metadata value type: " + value.getClass().getName());
        };
    }
}
