package lvregister.quality;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Run record and data quality report of one capture (PLAN.md section 11), stored as
 * {@code reports/date=D/report.json}. Counts only: no person data.
 */
public record QualityReport(
        int schemaVersion,
        String runId,
        Optional<String> dagsterRunId,
        LocalDate captureDate,
        Optional<LocalDate> previousCapture,
        Optional<Long> daysSincePrevious,
        Instant startedAt,
        Instant finishedAt,
        Status status,
        List<String> errors,
        List<Warning> warnings,
        List<FileReport> files,
        Snapshot snapshot,
        Map<String, Long> eventsByType,
        Map<String, Long> bucketBytes) {

    public static final int SCHEMA_VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public enum Status { OK, WARN, FAILED }

    /** A finding that does not stop the pipeline but must be looked at. {@code code} is stable. */
    public record Warning(String code, String message) {}

    /**
     * One source file.
     *
     * @param rowsReadPrevious rows of the same file in the previous capture
     * @param missingRequired  NULL count per required column
     */
    public record FileReport(
            String file,
            String sha256,
            long sizeBytes,
            long rowsRead,
            Optional<Long> rowsReadPrevious,
            long rowsKept,
            Map<String, Long> droppedByRule,
            Map<String, Long> flaggedByRule,
            Map<String, Long> missingRequired) {

        public FileReport {
            droppedByRule = new TreeMap<>(droppedByRule);
            flaggedByRule = new TreeMap<>(flaggedByRule);
            missingRequired = new TreeMap<>(missingRequired);
        }

        public long dropped() {
            return droppedByRule.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** Figures of the assembled snapshot. */
    public record Snapshot(
            long companies,
            long companiesWithRoles,
            long roles,
            long orphanRoles,
            long duplicateRoles,
            long duplicateRegNumbers,
            long unmappedLegalForms,
            long unmappedStatuses) {}

    public QualityReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
        files = List.copyOf(files);
        eventsByType = new TreeMap<>(eventsByType);
        bucketBytes = new TreeMap<>(bucketBytes);
    }

    public static String key(LocalDate date) {
        return "reports/date=" + date + "/report.json";
    }

    public long eventsTotal() {
        return eventsByType.values().stream().mapToLong(Long::longValue).sum();
    }

    public byte[] toJson() {
        return JSON.writeValueAsBytes(this);
    }

    public static QualityReport fromJson(byte[] json) {
        return JSON.readValue(json, QualityReport.class);
    }
}
