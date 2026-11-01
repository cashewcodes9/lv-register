package lvregister.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lvregister.quality.CheckResult;

/**
 * What a step reports to Dagster: materialization metadata (counts, sha256, URIs; never person
 * data) and the results of its asset checks.
 */
public record StepOutcome(Map<String, Object> metadata, List<CheckResult> checks) {

    public StepOutcome {
        metadata = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        checks = List.copyOf(checks);
    }

    public static StepOutcome of(Map<String, Object> metadata) {
        return new StepOutcome(metadata, List.of());
    }

    public static StepOutcome none() {
        return of(Map.of());
    }
}
