package io.github.cashewcodes9.lvregister.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RunContextTest {

    @Test
    void takesIdsFromTheEnvironment() {
        RunContext context = RunContext.fromEnvironment(Map.of("LV_RUN_ID", "r-1", "DAGSTER_RUN_ID", "d-1"));

        assertThat(context.runId()).isEqualTo("r-1");
        assertThat(context.dagsterRunId()).contains("d-1");
    }

    @Test
    void generatesARunIdAndOmitsABlankDagsterId() {
        RunContext context = RunContext.fromEnvironment(Map.of("LV_RUN_ID", " ", "DAGSTER_RUN_ID", ""));

        assertThat(context.runId()).isNotBlank().isNotEqualTo(" ");
        assertThat(context.dagsterRunId()).isEmpty();
    }
}
