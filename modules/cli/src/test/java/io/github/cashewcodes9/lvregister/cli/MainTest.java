package io.github.cashewcodes9.lvregister.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import picocli.CommandLine;

class MainTest {

    private static final RunContext CONTEXT = new RunContext("test-run", Optional.empty());

    /** Steps that record which step ran for which date. */
    private static Steps recording(List<String> calls) {
        Map<StepName, Step> steps = new EnumMap<>(StepName.class);
        for (StepName name : StepName.values()) {
            steps.put(name, date -> calls.add(name.cliName() + "@" + date));
        }
        return new Steps(steps);
    }

    private static CommandLine quiet(CommandLine cli) {
        cli.setErr(new PrintWriter(new StringWriter()));
        cli.setOut(new PrintWriter(new StringWriter()));
        return cli;
    }

    @ParameterizedTest
    @EnumSource(StepName.class)
    void everyStepSubcommandParsesDateAndRunsItsStep(StepName step) {
        List<String> calls = new ArrayList<>();

        int exit = Main.newCommandLine(recording(calls), CONTEXT).execute(step.cliName(), "--date", "2026-11-02");

        assertThat(exit).isZero();
        assertThat(calls).containsExactly(step.cliName() + "@2026-11-02");
    }

    @Test
    void runExecutesAllStepsInOrder() {
        List<String> calls = new ArrayList<>();

        int exit = Main.newCommandLine(recording(calls), CONTEXT).execute("run", "--date", "2026-11-02");

        assertThat(exit).isZero();
        assertThat(calls).containsExactly(
                "download@2026-11-02", "lake@2026-11-02", "diff@2026-11-02",
                "quality@2026-11-02", "index@2026-11-02", "prune@2026-11-02");
    }

    @Test
    void missingOrInvalidDateIsAUsageError() {
        List<String> calls = new ArrayList<>();

        assertThat(quiet(Main.newCommandLine(recording(calls), CONTEXT)).execute("download")).isEqualTo(2);
        assertThat(quiet(Main.newCommandLine(recording(calls), CONTEXT)).execute("download", "--date", "2026-13-01"))
                .isEqualTo(2);
        assertThat(calls).isEmpty();
    }

    @Test
    void notImplementedStepExitsWithOne() {
        int exit = Main.newCommandLine(Steps.notImplemented(), CONTEXT).execute("lake", "--date", "2026-11-02");

        assertThat(exit).isEqualTo(1);
    }

    @Test
    void runStopsAtTheFirstFailingStep() {
        List<String> calls = new ArrayList<>();
        Map<StepName, Step> steps = new EnumMap<>(recording(calls).byName());
        steps.put(StepName.DIFF, date -> { throw new IllegalStateException("boom"); });

        int exit = Main.newCommandLine(new Steps(steps), CONTEXT).execute("run", "--date", "2026-11-02");

        assertThat(exit).isEqualTo(1);
        assertThat(calls).containsExactly("download@2026-11-02", "lake@2026-11-02");
    }

    @Test
    void stepsRequireAnImplementationForEveryStep() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new Steps(Map.of(StepName.DOWNLOAD, (LocalDate d) -> { })));
    }
}
