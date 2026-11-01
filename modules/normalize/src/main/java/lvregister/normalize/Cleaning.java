package lvregister.normalize;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import lvregister.domain.Cleaned;

/** Folds row outcomes into {@link Cleaned}: kept values in source order, counts per rule. */
public final class Cleaning {

    private Cleaning() {}

    public static <T> Cleaned<T> collect(Stream<Outcome<T>> outcomes) {
        List<T> rows = new ArrayList<>();
        Map<String, Long> dropped = new TreeMap<>();
        Map<String, Long> flagged = new TreeMap<>();
        outcomes.forEachOrdered(outcome -> {
            switch (outcome) {
                case Outcome.Kept<T>(T value, List<String> flags) -> {
                    rows.add(value);
                    flags.forEach(rule -> flagged.merge(rule, 1L, Long::sum));
                }
                case Outcome.Dropped<T>(String rule) -> dropped.merge(rule, 1L, Long::sum);
            }
        });
        return new Cleaned<>(rows, dropped, flagged);
    }

    /** Keeps the first row per key and counts the others as dropped under {@code rule}. */
    public static <T, K> Cleaned<T> dropDuplicates(Cleaned<T> cleaned, Function<T, K> key, String rule) {
        Set<K> seen = new HashSet<>();
        List<T> rows = new ArrayList<>();
        long duplicates = 0;
        for (T row : cleaned.rows()) {
            if (seen.add(key.apply(row))) {
                rows.add(row);
            } else {
                duplicates++;
            }
        }
        Map<String, Long> dropped = new TreeMap<>(cleaned.droppedByRule());
        if (duplicates > 0) {
            dropped.merge(rule, duplicates, Long::sum);
        }
        return new Cleaned<>(rows, dropped, cleaned.flaggedByRule());
    }
}
