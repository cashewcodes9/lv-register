package io.github.cashewcodes9.lvregister.cli;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Identifies one CLI run in every log line: {@code run_id}, {@code dagster_run_id},
 * {@code step} and {@code partition_date} in the SLF4J MDC.
 */
public record RunContext(String runId, Optional<String> dagsterRunId) {

    public static final String RUN_ID = "run_id";
    public static final String DAGSTER_RUN_ID = "dagster_run_id";
    public static final String STEP = "step";
    public static final String PARTITION_DATE = "partition_date";

    public RunContext {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        dagsterRunId = dagsterRunId == null ? Optional.empty() : dagsterRunId.filter(s -> !s.isBlank());
    }

    /** Run id from {@code LV_RUN_ID} if set, else a new UUID; Dagster run id from {@code DAGSTER_RUN_ID}. */
    public static RunContext fromEnvironment(Map<String, String> env) {
        String runId = Optional.ofNullable(env.get("LV_RUN_ID")).filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
        return new RunContext(runId, Optional.ofNullable(env.get("DAGSTER_RUN_ID")));
    }

    void enter(StepName step, LocalDate date) {
        MDC.put(RUN_ID, runId);
        dagsterRunId.ifPresent(id -> MDC.put(DAGSTER_RUN_ID, id));
        MDC.put(STEP, step.cliName());
        MDC.put(PARTITION_DATE, date.toString());
    }

    static void leave() {
        MDC.remove(STEP);
        MDC.remove(PARTITION_DATE);
    }
}
