package lvregister.cli;

import java.time.LocalDate;

/** One pipeline step for one capture date. Implementations live in the pipeline modules. */
@FunctionalInterface
public interface Step {

    /** Runs the step; the outcome is reported to Dagster when the CLI runs under Pipes. */
    StepOutcome run(LocalDate captureDate) throws Exception;
}
