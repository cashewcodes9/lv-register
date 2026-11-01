package lvregister.cli;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lvregister.pipes.PipesSession;
import lvregister.pipes.Severity;
import lvregister.quality.CheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs steps with the run context in the MDC and logs start, end and failure. Under Dagster
 * Pipes it reports the outcome of the step: the materialization, then each check (a failed
 * blocking check with severity ERROR, which stops downstream assets; others WARN).
 */
public final class StepRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StepRunner.class);

    private final Steps steps;
    private final RunContext context;
    private final Optional<PipesSession> pipes;

    public StepRunner(Steps steps, RunContext context, Optional<PipesSession> pipes) {
        this.steps = steps;
        this.context = context;
        this.pipes = pipes;
    }

    /** Returns the process exit code: 0 on success, 1 when a step failed. */
    public int run(List<StepName> names, LocalDate date) {
        if (pipes.isPresent() && names.size() != 1) {
            LOG.error("under Dagster Pipes the CLI runs one step per process, got {}", names);
            return 1;
        }
        for (StepName name : names) {
            context.enter(name, date);
            Instant start = Instant.now();
            try {
                LOG.info("step started");
                StepOutcome outcome = steps.get(name).run(date);
                pipes.ifPresent(session -> report(session, outcome));
                long failed = outcome.checks().stream().filter(c -> !c.passed()).count();
                LOG.info("step finished in {} ms, {} of {} checks failed", Duration.between(start, Instant.now()).toMillis(),
                        failed, outcome.checks().size());
            } catch (Exception e) {
                LOG.error("step failed: {}", e.getMessage(), e);
                return 1;
            } finally {
                RunContext.leave();
            }
        }
        return 0;
    }

    private static void report(PipesSession session, StepOutcome outcome) {
        session.reportMaterialization(outcome.metadata());
        for (CheckResult check : outcome.checks()) {
            Severity severity = check.blocking() ? Severity.ERROR : Severity.WARN;
            session.reportCheck(check.name(), check.passed(), severity, check.metadata());
        }
    }
}
