package lvregister.normalize;

import java.util.List;
import java.util.function.Function;

/** What happened to one source row: kept as a value (possibly with flags) or dropped by a rule. */
public sealed interface Outcome<T> {

    record Kept<T>(T value, List<String> flags) implements Outcome<T> {
        public Kept {
            flags = List.copyOf(flags);
        }
    }

    record Dropped<T>(String rule) implements Outcome<T> {}

    static <T> Outcome<T> kept(T value, Row row) {
        return new Kept<>(value, row.flags());
    }

    static <T> Outcome<T> dropped(String rule) {
        return new Dropped<>(rule);
    }

    default <R> Outcome<R> map(Function<T, R> f) {
        return switch (this) {
            case Kept<T>(T value, List<String> flags) -> new Kept<>(f.apply(value), flags);
            case Dropped<T>(String rule) -> new Dropped<>(rule);
        };
    }
}
