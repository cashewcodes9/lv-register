package lvregister.serving;

import java.time.LocalDate;

/** One pipeline run with its quality report (the report JSON as written by the quality step). */
public record RunView(String runId, LocalDate captureDate, String status, String reportJson) {}
