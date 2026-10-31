package lvregister.storage;

import java.util.regex.Pattern;

/** Validation of object keys, shared by the implementations. */
public final class Keys {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._=/-]+");

    private Keys() {}

    /** Returns the key if it is a safe relative key, else throws. */
    public static String check(String key) {
        if (key == null || key.isEmpty() || key.startsWith("/") || key.endsWith("/")
                || key.contains("//") || !ALLOWED.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid object key: " + key);
        }
        for (String part : key.split("/")) {
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("invalid object key: " + key);
            }
        }
        return key;
    }

    /** Prefixes may be empty or end with a slash. */
    public static String checkPrefix(String prefix) {
        if (prefix.isEmpty()) {
            return prefix;
        }
        String trimmed = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        check(trimmed);
        return prefix;
    }
}
