package lvregister.domain;

import java.util.List;
import java.util.Map;

/**
 * Result of a cleaning step: kept rows, the number of dropped rows per rule, and the number of
 * kept rows that were flagged (kept, but with a value mapped to a default or corrected) per
 * rule. Unknown values are never dropped silently: they are counted in one of the two maps.
 */
public record Cleaned<T>(List<T> rows, Map<String, Long> droppedByRule, Map<String, Long> flaggedByRule) {

    public Cleaned {
        rows = List.copyOf(Checks.notNull(rows, "rows"));
        droppedByRule = Map.copyOf(Checks.notNull(droppedByRule, "droppedByRule"));
        flaggedByRule = Map.copyOf(Checks.notNull(flaggedByRule, "flaggedByRule"));
        if (droppedByRule.values().stream().anyMatch(n -> n < 0) || flaggedByRule.values().stream().anyMatch(n -> n < 0)) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }

    public long droppedTotal() {
        return droppedByRule.values().stream().mapToLong(Long::longValue).sum();
    }

    public long flaggedTotal() {
        return flaggedByRule.values().stream().mapToLong(Long::longValue).sum();
    }
}
