package lvregister.serving;

import java.util.List;

/** One page of a result list; {@code page} counts from 0. */
public record Page<T>(List<T> items, int page, int size, long total) {

    public static final int MAX_SIZE = 100;

    public Page {
        items = List.copyOf(items);
    }

    /** Validates paging arguments, shared by all backends. */
    public static void check(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and " + MAX_SIZE);
        }
    }
}
