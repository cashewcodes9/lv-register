package lvregister.quality;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lvregister.ingest.Manifest;
import lvregister.ingest.ManifestEntry;
import lvregister.ingest.SourceFile;
import lvregister.lake.CaptureCounts;
import lvregister.lake.SnapshotLoader;
import lvregister.normalize.LegalForms;
import lvregister.normalize.RegisterNormalizer;
import lvregister.normalize.Statuses;

/** Builds the quality report from what the steps wrote. Pure: all inputs are given. */
public final class ReportBuilder {

    /** Everything the report is made of, read by {@link QualityStep}. */
    public record Inputs(
            String runId,
            Optional<String> dagsterRunId,
            LocalDate captureDate,
            Optional<LocalDate> previousCapture,
            Manifest manifest,
            Map<SourceFile, Long> rowsRead,
            Map<SourceFile, Long> rowsReadPrevious,
            Map<SourceFile, Map<String, Long>> missingRequired,
            CaptureCounts counts,
            Map<String, Long> eventsByType,
            Map<String, Long> bucketBytes,
            Instant finishedAt) {}

    private ReportBuilder() {}

    public static QualityReport build(Inputs in, Thresholds t) {
        List<QualityReport.Warning> warnings = new ArrayList<>();
        List<QualityReport.FileReport> files = new ArrayList<>();
        for (SourceFile file : SourceFile.values()) {
            ManifestEntry entry = in.manifest().entry(file).orElseThrow();
            SnapshotLoader.FileCounts c = in.counts().files().get(file.baseName());
            long read = in.rowsRead().getOrDefault(file, 0L);
            Optional<Long> previous = Optional.ofNullable(in.rowsReadPrevious().get(file));
            QualityReport.FileReport report = new QualityReport.FileReport(file.baseName(), entry.sha256(), entry.size(), read,
                    previous, c == null ? 0 : c.kept(), c == null ? Map.of() : c.droppedByRule(),
                    c == null ? Map.of() : c.flaggedByRule(), in.missingRequired().getOrDefault(file, Map.of()));
            files.add(report);
            long dropped = report.droppedByRule().entrySet().stream()
                    .filter(e -> !e.getKey().contains(".legacy_")).mapToLong(Map.Entry::getValue).sum();
            if (read > 0 && (double) dropped / read > t.dropShare()) {
                warnings.add(new QualityReport.Warning("drop_share", "%s: %d of %d rows dropped (%.2f %%)"
                        .formatted(file.baseName(), dropped, read, 100.0 * dropped / read)));
            }
            previous.filter(p -> p > 0 && Math.abs(read - p) / (double) p > t.rowChangeShare()).ifPresent(p ->
                    warnings.add(new QualityReport.Warning("row_change", "%s: %d rows, %d in the previous capture"
                            .formatted(file.baseName(), read, p))));
            if (read == 0 && file == SourceFile.REGISTER) {
                warnings.add(new QualityReport.Warning("empty_register", "register has no rows"));
            }
        }

        CaptureCounts counts = in.counts();
        long unmappedForms = flagged(counts, "register", LegalForms.UNMAPPED);
        long unmappedStatuses = flagged(counts, "register", Statuses.UNMAPPED_CLOSED)
                + flagged(counts, "insolvency_legal_person_proceeding", Statuses.UNMAPPED_FORM);
        long duplicates = dropped(counts, "register", RegisterNormalizer.DUPLICATE_REGCODE);
        QualityReport.Snapshot snapshot = new QualityReport.Snapshot(counts.companies(), counts.companiesWithRoles(),
                counts.roles(), counts.orphanRoles(), counts.duplicateRoles(), duplicates, unmappedForms, unmappedStatuses);
        if (unmappedForms > 0) {
            warnings.add(new QualityReport.Warning("unmapped_legal_form", unmappedForms + " companies with an unmapped legal form"));
        }
        if (unmappedStatuses > 0) {
            warnings.add(new QualityReport.Warning("unmapped_status", unmappedStatuses + " unmapped closed flags or proceeding forms"));
        }
        if (counts.orphanRoles() > 0) {
            warnings.add(new QualityReport.Warning("orphan_roles", counts.orphanRoles() + " roles point to unknown companies"));
        }
        if (duplicates > 0) {
            warnings.add(new QualityReport.Warning("duplicate_regcodes", duplicates + " duplicate registration numbers dropped"));
        }

        long spikeLevel = Math.max(t.eventSpikeMin(), Math.round(t.eventSpikeShare() * counts.companies()));
        in.eventsByType().forEach((type, n) -> {
            if (n > spikeLevel) {
                warnings.add(new QualityReport.Warning("event_spike", "%s: %d events (warning level %d)".formatted(type, n, spikeLevel)));
            }
        });
        long missing = in.eventsByType().getOrDefault("MISSING_FROM_SNAPSHOT", 0L);
        if (missing > 0) {
            warnings.add(new QualityReport.Warning("missing_from_snapshot", missing + " companies vanished from the register"));
        }
        long storage = in.bucketBytes().values().stream().mapToLong(Long::longValue).sum();
        if (storage > t.storageWarnBytes()) {
            warnings.add(new QualityReport.Warning("storage", "buckets hold %d MB".formatted(storage / (1024 * 1024))));
        }

        Instant started = in.manifest().files().stream().map(ManifestEntry::fetchedAt).min(Instant::compareTo)
                .orElse(in.finishedAt());
        return new QualityReport(QualityReport.SCHEMA_VERSION, in.runId(), in.dagsterRunId(), in.captureDate(),
                in.previousCapture(), in.previousCapture().map(p -> ChronoUnit.DAYS.between(p, in.captureDate())),
                started, in.finishedAt(), warnings.isEmpty() ? QualityReport.Status.OK : QualityReport.Status.WARN,
                List.of(), warnings, files, snapshot, in.eventsByType(), in.bucketBytes());
    }

    /** A report for a capture whose inputs are incomplete: status FAILED with the reasons. */
    public static QualityReport failed(String runId, Optional<String> dagsterRunId, LocalDate date, List<String> errors, Instant now) {
        return new QualityReport(QualityReport.SCHEMA_VERSION, runId, dagsterRunId, date, Optional.empty(), Optional.empty(),
                now, now, QualityReport.Status.FAILED, errors, List.of(), List.of(),
                new QualityReport.Snapshot(0, 0, 0, 0, 0, 0, 0, 0), Map.of(), Map.of());
    }

    private static long flagged(CaptureCounts counts, String file, String rule) {
        SnapshotLoader.FileCounts c = counts.files().get(file);
        return c == null ? 0 : c.flaggedByRule().getOrDefault(rule, 0L);
    }

    private static long dropped(CaptureCounts counts, String file, String rule) {
        SnapshotLoader.FileCounts c = counts.files().get(file);
        return c == null ? 0 : c.droppedByRule().getOrDefault(rule, 0L);
    }
}
