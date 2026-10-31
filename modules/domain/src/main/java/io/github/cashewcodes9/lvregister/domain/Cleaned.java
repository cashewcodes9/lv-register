package io.github.cashewcodes9.lvregister.domain;

import java.util.List;
import java.util.Map;

/** Result of a cleaning step: kept rows and the number of dropped rows per rule. */
public record Cleaned<T>(List<T> rows, Map<String, Long> droppedByRule) {

    public Cleaned {
        rows = List.copyOf(Checks.notNull(rows, "rows"));
        droppedByRule = Map.copyOf(Checks.notNull(droppedByRule, "droppedByRule"));
        if (droppedByRule.values().stream().anyMatch(n -> n < 0)) {
            throw new IllegalArgumentException("dropped counts must not be negative");
        }
    }

    public long droppedTotal() {
        return droppedByRule.values().stream().mapToLong(Long::longValue).sum();
    }
}
