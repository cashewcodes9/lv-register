package lvregister.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lvregister.pipes.PipesParams;
import lvregister.pipes.PipesSession;
import lvregister.quality.CheckResult;
import org.junit.jupiter.api.Test;

class StepRunnerPipesTest {

    private static final RunContext CONTEXT = new RunContext("test-run", Optional.of("dagster-run"));

    private final ByteArrayOutputStream messages = new ByteArrayOutputStream();

    @Test
    void reportsTheMaterializationAndEveryCheckWithItsSeverity() {
        Steps steps = steps(new StepOutcome(Map.of("events", 4L), List.of(
                new CheckResult("event_spike", false, true, Map.of("limit", 49_000L)),
                new CheckResult("event_volume", false, false, Map.of()),
                new CheckResult("drop_share", true, true, Map.of()))));

        try (PipesSession session = session()) {
            int exit = Main.newCommandLine(steps, CONTEXT, Optional.of(session)).execute("diff", "--date", "2026-11-09");
            assertThat(exit).isZero();
        }

        List<String> lines = messages.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(6);
        assertThat(lines.get(1)).contains("\"method\":\"report_asset_materialization\"", "\"asset_key\":\"company_events\"",
                "\"events\":{\"raw_value\":4,\"type\":\"int\"}");
        assertThat(lines.get(2)).contains("\"check_name\":\"event_spike\"", "\"passed\":false", "\"severity\":\"ERROR\"");
        assertThat(lines.get(3)).contains("\"check_name\":\"event_volume\"", "\"severity\":\"WARN\"");
        assertThat(lines.get(4)).contains("\"check_name\":\"drop_share\"", "\"passed\":true");
    }

    @Test
    void underPipesTheRunCommandIsRefused() {
        try (PipesSession session = session()) {
            int exit = Main.newCommandLine(steps(StepOutcome.none()), CONTEXT, Optional.of(session))
                    .execute("run", "--date", "2026-11-09");
            assertThat(exit).isEqualTo(1);
        }
        assertThat(messages.toString(StandardCharsets.UTF_8)).doesNotContain("report_asset_materialization");
    }

    @Test
    void aFailingStepReportsNothing() {
        Map<StepName, Step> failing = new EnumMap<>(steps(StepOutcome.none()).byName());
        failing.put(StepName.DIFF, date -> { throw new IllegalStateException("boom"); });
        try (PipesSession session = session()) {
            assertThat(Main.newCommandLine(new Steps(failing), CONTEXT, Optional.of(session))
                    .execute("diff", "--date", "2026-11-09")).isEqualTo(1);
        }
        assertThat(messages.toString(StandardCharsets.UTF_8).lines()).hasSize(2);
    }

    private PipesSession session() {
        String context = PipesParams.encode(Map.of("data", Map.of("asset_keys", List.of("company_events"),
                "partition_key", "2026-11-09", "run_id", "dagster-run")));
        PrintStream stream = new PrintStream(messages, true, StandardCharsets.UTF_8);
        return PipesSession.open(Map.of(PipesSession.CONTEXT_ENV, context,
                PipesSession.MESSAGES_ENV, PipesParams.encode(Map.of("stdio", "stderr"))), System.out, stream).orElseThrow();
    }

    private static Steps steps(StepOutcome outcome) {
        Map<StepName, Step> steps = new EnumMap<>(StepName.class);
        for (StepName name : StepName.values()) {
            steps.put(name, date -> outcome);
        }
        return new Steps(steps);
    }
}
