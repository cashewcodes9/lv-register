package lvregister.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lvregister.ingest.Manifest;
import lvregister.ingest.ManifestEntry;
import lvregister.ingest.SourceFile;
import lvregister.lake.CaptureCounts;
import lvregister.lake.SnapshotLoader;
import org.junit.jupiter.api.Test;

class ReportBuilderTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 9);
    private static final Instant FETCHED = Instant.parse("2026-11-09T04:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-09T04:10:00Z");

    private static Manifest manifest() {
        Manifest m = Manifest.empty(D2);
        for (SourceFile f : SourceFile.values()) {
            m = m.with(new ManifestEntry(f.csvName(), URI.create("https://example.org/" + f.csvName()), "0".repeat(64), 1000,
                    FETCHED, Optional.empty()));
        }
        return m;
    }

    private static Map<SourceFile, Long> rows(long n) {
        Map<SourceFile, Long> rows = new EnumMap<>(SourceFile.class);
        for (SourceFile f : SourceFile.values()) {
            rows.put(f, n);
        }
        return rows;
    }

    private static CaptureCounts counts(Map<String, SnapshotLoader.FileCounts> overrides, long orphans) {
        Map<String, SnapshotLoader.FileCounts> files = new HashMap<>();
        for (SourceFile f : SourceFile.values()) {
            files.put(f.baseName(), new SnapshotLoader.FileCounts(1000, Map.of(), Map.of()));
        }
        files.putAll(overrides);
        return new CaptureCounts(D2, 100_000, 50_000, 150_000, orphans, 0, files);
    }

    private static ReportBuilder.Inputs inputs(Map<SourceFile, Long> rowsRead, Map<SourceFile, Long> previous,
            CaptureCounts counts, Map<String, Long> events) {
        return new ReportBuilder.Inputs("run-1", Optional.of("dagster-1"), D2, Optional.of(D1), manifest(), rowsRead, previous,
                Map.of(SourceFile.REGISTER, Map.of("name", 2L)), counts, events, Map.of("lv-raw", 10L, "lv-lake", 20L), NOW);
    }

    @Test
    void cleanCaptureIsOk() {
        QualityReport r = ReportBuilder.build(inputs(rows(1000), rows(1000), counts(Map.of(), 0),
                Map.of("NAME_CHANGED", 12L)), Thresholds.defaults());

        assertThat(r.status()).isEqualTo(QualityReport.Status.OK);
        assertThat(r.warnings()).isEmpty();
        assertThat(r.daysSincePrevious()).contains(7L);
        assertThat(r.startedAt()).isEqualTo(FETCHED);
        assertThat(r.finishedAt()).isEqualTo(NOW);
        assertThat(r.files()).hasSize(SourceFile.values().length);
        assertThat(r.files().getFirst().missingRequired()).containsEntry("name", 2L);
        assertThat(r.eventsTotal()).isEqualTo(12);
        assertThat(r.dagsterRunId()).contains("dagster-1");
    }

    @Test
    void everyRuleWarns() {
        CaptureCounts counts = counts(Map.of(
                "officers", new SnapshotLoader.FileCounts(970, Map.of("officers.invalid_company", 30L), Map.of()),
                "register", new SnapshotLoader.FileCounts(1000, Map.of("register.duplicate_regcode", 1L),
                        Map.of("register.unmapped_legal_form", 3L, "register.unmapped_closed_flag", 1L))), 5);
        Map<SourceFile, Long> previous = rows(1000);
        previous.put(SourceFile.MEMBERS, 800L);
        Thresholds t = new Thresholds(0.01, 0.05, 0.01, 500, 25, 0.10);

        QualityReport r = ReportBuilder.build(inputs(rows(1000), previous, counts,
                Map.of("MANAGEMENT_CHANGED", 1001L, "MISSING_FROM_SNAPSHOT", 2L)), t);

        assertThat(r.status()).isEqualTo(QualityReport.Status.WARN);
        assertThat(r.warnings()).extracting(QualityReport.Warning::code).containsExactlyInAnyOrder("drop_share", "row_change",
                "unmapped_legal_form", "unmapped_status", "orphan_roles", "duplicate_regcodes", "event_spike",
                "missing_from_snapshot", "storage");
        assertThat(r.snapshot().unmappedLegalForms()).isEqualTo(3);
        assertThat(r.snapshot().duplicateRegNumbers()).isEqualTo(1);
    }

    @Test
    void legacyDropsDoNotCountForTheDropShare() {
        CaptureCounts counts = counts(Map.of("reorganizations",
                new SnapshotLoader.FileCounts(985, Map.of("reorganizations.legacy_regcode", 15L), Map.of())), 0);

        QualityReport r = ReportBuilder.build(inputs(rows(1000), rows(1000), counts, Map.of()), Thresholds.defaults());

        assertThat(r.warnings()).isEmpty();
        assertThat(r.files()).filteredOn(f -> f.file().equals("reorganizations")).singleElement()
                .satisfies(f -> assertThat(f.dropped()).isEqualTo(15));
    }

    @Test
    void eventSpikeLevelScalesWithCompanies() {
        QualityReport r = ReportBuilder.build(inputs(rows(1000), rows(1000), counts(Map.of(), 0),
                Map.of("MANAGEMENT_CHANGED", 999L)), Thresholds.defaults());

        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void jsonRoundTrip() {
        QualityReport r = ReportBuilder.build(inputs(rows(1000), rows(990), counts(Map.of(), 0), Map.of("NAME_CHANGED", 1L)),
                Thresholds.defaults());

        assertThat(QualityReport.fromJson(r.toJson())).isEqualTo(r);
    }

    @Test
    void failedReportCarriesTheErrors() {
        QualityReport r = ReportBuilder.failed("run-1", Optional.empty(), D2, java.util.List.of("no manifest"), NOW);

        assertThat(r.status()).isEqualTo(QualityReport.Status.FAILED);
        assertThat(QualityReport.fromJson(r.toJson()).errors()).containsExactly("no manifest");
    }
}
