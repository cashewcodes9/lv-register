package lvregister.cli;

import java.time.LocalDate;

/** One pipeline step for one capture date. Implementations live in the pipeline modules. */
@FunctionalInterface
public interface Step {

    void run(LocalDate captureDate) throws Exception;
}
