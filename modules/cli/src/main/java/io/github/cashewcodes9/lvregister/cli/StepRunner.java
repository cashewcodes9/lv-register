package io.github.cashewcodes9.lvregister.cli;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs steps with the run context in the MDC and logs start, end and failure. */
public final class StepRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StepRunner.class);

    private final Steps steps;
    private final RunContext context;

    public StepRunner(Steps steps, RunContext context) {
        this.steps = steps;
        this.context = context;
    }

    /** Returns the process exit code: 0 on success, 1 when a step failed. */
    public int run(List<StepName> names, LocalDate date) {
        for (StepName name : names) {
            context.enter(name, date);
            Instant start = Instant.now();
            try {
                LOG.info("step started");
                steps.get(name).run(date);
                LOG.info("step finished in {} ms", Duration.between(start, Instant.now()).toMillis());
            } catch (Exception e) {
                LOG.error("step failed: {}", e.getMessage(), e);
                return 1;
            } finally {
                RunContext.leave();
            }
        }
        return 0;
    }
}
