package lvregister.quality;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lvregister.ingest.Manifest;
import lvregister.ingest.ManifestEntry;
import lvregister.ingest.SourceFile;

/**
 * The asset checks of each step (docs/architecture.md, Orchestration with Dagster). Pure:
 * the CLI reads the inputs and reports the results to Dagster.
 */
public final class Checks {

    // ur_raw_files
    public static final String FILES_COMPLETE = "files_complete";
    public static final String SIZE_CHANGE = "size_change";
    // lake_snapshot
    public static final String SCHEMA_UNCHANGED = "schema_unchanged";
    // company_events
    public static final String EVENT_SPIKE = "event_spike";
    // quality_report
    public static final String DROP_SHARE = "drop_share";
    public static final String REGISTER_NOT_EMPTY = "register_not_empty";
    public static final String ORPHAN_ROLES = "orphan_roles";
    public static final String UNMAPPED_VALUES = "unmapped_values";
    public static final String ROW_CHANGE = "row_change";
    public static final String DUPLICATE_REGCODES = "duplicate_regcodes";
    public static final String EVENT_VOLUME = "event_volume";
    // search_index
    public static final String COUNTS_MATCH_MARTS = "counts_match_marts";
    // retention
    public static final String STORAGE_BELOW_WARN = "storage_below_warn";

    private Checks() {}

    /** Every MVP file is in the manifest and not empty (blocking); sizes vs the previous capture (warn). */
    public static List<CheckResult> rawFiles(Manifest current, Optional<Manifest> previous, Thresholds t) {
        List<String> missing = new ArrayList<>();
        for (SourceFile file : SourceFile.values()) {
            if (current.entry(file).map(e -> e.size() == 0).orElse(true)) {
                missing.add(file.csvName());
            }
        }
        Map<String, Object> changed = new TreeMap<>();
        previous.ifPresent(p -> {
            for (SourceFile file : SourceFile.values()) {
                Optional<Long> before = p.entry(file).map(ManifestEntry::size).filter(s -> s > 0);
                Optional<Long> now = current.entry(file).map(ManifestEntry::size);
                if (before.isPresent() && now.isPresent()
                        && Math.abs(now.get() - before.get()) / (double) before.get() > t.rowChangeShare()) {
                    changed.put(file.csvName(), Map.of("bytes", now.get(), "previous_bytes", before.get()));
                }
            }
        });
        return List.of(
                new CheckResult(FILES_COMPLETE, missing.isEmpty(), true,
                        Map.of("files", (long) current.files().size(), "missing_or_empty", missing)),
                new CheckResult(SIZE_CHANGE, changed.isEmpty(), false, Map.of(
                        "previous_capture", previous.map(m -> m.captureDate().toString()).orElse("none"),
                        "warn_share", t.rowChangeShare(), "changed", changed)));
    }

    /**
     * No column of the previous capture is missing (blocking). New columns pass and are listed.
     *
     * @param current  columns per file of this capture
     * @param previous columns per file of the previous capture, empty for the baseline
     */
    public static CheckResult schema(Map<String, List<String>> current, Optional<Map<String, List<String>>> previous) {
        Map<String, Object> removed = new TreeMap<>();
        Map<String, Object> added = new TreeMap<>();
        previous.ifPresent(p -> p.forEach((file, before) -> {
            Set<String> now = new TreeSet<>(current.getOrDefault(file, List.of()));
            Set<String> gone = new TreeSet<>(before);
            gone.removeAll(now);
            Set<String> fresh = new TreeSet<>(now);
            fresh.removeAll(before);
            if (!gone.isEmpty()) {
                removed.put(file, List.copyOf(gone));
            }
            if (!fresh.isEmpty()) {
                added.put(file, List.copyOf(fresh));
            }
        }));
        return new CheckResult(SCHEMA_UNCHANGED, removed.isEmpty(), true, Map.of(
                "compared_with_previous", previous.isPresent(), "removed_columns", removed, "added_columns", added));
    }

    /** No event type above {@code eventHardShare} of the companies (blocking). */
    public static CheckResult events(Map<String, Long> eventsByType, long companies, Thresholds t) {
        long limit = Math.max(t.eventSpikeMin(), Math.round(t.eventHardShare() * companies));
        Map<String, Object> above = new TreeMap<>();
        eventsByType.forEach((type, n) -> {
            if (n > limit) {
                above.put(type, n);
            }
        });
        return new CheckResult(EVENT_SPIKE, above.isEmpty(), true,
                Map.of("limit", limit, "companies", companies, "above_limit", above));
    }

    /** The report's warnings as checks; drop share and an empty register block. */
    public static List<CheckResult> report(QualityReport report) {
        return List.of(
                fromWarnings(report, DROP_SHARE, true, Set.of("drop_share")),
                fromWarnings(report, REGISTER_NOT_EMPTY, true, Set.of("empty_register")),
                fromWarnings(report, ORPHAN_ROLES, false, Set.of("orphan_roles")),
                fromWarnings(report, UNMAPPED_VALUES, false, Set.of("unmapped_legal_form", "unmapped_status")),
                fromWarnings(report, ROW_CHANGE, false, Set.of("row_change")),
                fromWarnings(report, DUPLICATE_REGCODES, false, Set.of("duplicate_regcodes")),
                fromWarnings(report, EVENT_VOLUME, false, Set.of("missing_from_snapshot", "event_spike")));
    }

    /**
     * Companies written to the serving store match {@code dim_companies} (warn). Only for the
     * latest capture: the mart holds the latest state.
     */
    public static CheckResult indexCounts(LocalDate date, LocalDate latestCapture, long indexed, Optional<Long> martCompanies) {
        if (!date.equals(latestCapture) || martCompanies.isEmpty()) {
            return new CheckResult(COUNTS_MATCH_MARTS, true, false, Map.of("indexed_companies", indexed,
                    "compared", false, "reason", martCompanies.isEmpty() ? "no dim_companies mart" : "not the latest capture"));
        }
        return new CheckResult(COUNTS_MATCH_MARTS, indexed == martCompanies.get(), false, Map.of(
                "indexed_companies", indexed, "mart_companies", martCompanies.get(), "compared", true));
    }

    /** Total bucket size at most {@code STORAGE_WARN_GB} (warn). */
    public static CheckResult storage(Map<String, Long> bucketBytes, Thresholds t) {
        long total = bucketBytes.values().stream().mapToLong(Long::longValue).sum();
        return new CheckResult(STORAGE_BELOW_WARN, total <= t.storageWarnBytes(), false, Map.of(
                "total_bytes", total, "warn_bytes", t.storageWarnBytes(), "bucket_bytes", new TreeMap<>(bucketBytes)));
    }

    private static CheckResult fromWarnings(QualityReport report, String name, boolean blocking, Set<String> codes) {
        List<String> messages = report.warnings().stream().filter(w -> codes.contains(w.code()))
                .map(QualityReport.Warning::message).toList();
        return new CheckResult(name, messages.isEmpty(), blocking, Map.of("warnings", messages,
                "report_status", report.status().name()));
    }
}
