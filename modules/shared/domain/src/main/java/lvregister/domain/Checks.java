package lvregister.domain;

import java.util.Objects;

/** Small argument checks for compact constructors. */
final class Checks {

    private Checks() {}

    static <T> T notNull(T value, String name) {
        return Objects.requireNonNull(value, () -> name + " must not be null");
    }

    static String notBlank(String value, String name) {
        notNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
