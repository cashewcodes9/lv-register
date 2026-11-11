package lvregister.domain;

import java.util.regex.Pattern;

/**
 * UR registration number: 11 digits, or 9 digits for old-style codes (which may start with 0).
 * Always a string, never a number, so leading zeros survive.
 */
public record RegNumber(String value) implements Comparable<RegNumber> {

    private static final Pattern FORMAT = Pattern.compile("\\d{9}|\\d{11}");

    public RegNumber {
        Checks.notNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("registration number must have 9 or 11 digits");
        }
    }

    @Override
    public int compareTo(RegNumber other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
