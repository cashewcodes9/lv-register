package lvregister.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lvregister.ingest.Manifest;
import lvregister.ingest.ManifestEntry;
import lvregister.ingest.SourceFile;
import org.junit.jupiter.api.Test;

class ChecksTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 9);
    private static final Thresholds T = Thresholds.defaults();

    @Test
    void rawFilesBlockOnAMissingOrEmptyFileAndWarnOnSizeChanges() {
        Manifest before = manifest(D1, 1000);
        Manifest same = manifest(D2, 1010);
        assertThat(Checks.rawFiles(same, Optional.of(before), T)).allMatch(CheckResult::passed);

        Manifest grown = manifest(D2, 2000);
        List<CheckResult> checks = Checks.rawFiles(grown, Optional.of(before), T);
        assertThat(checks.get(0).passed()).isTrue();
        assertThat(checks.get(1)).extracting(CheckResult::name, CheckResult::passed, CheckResult::blocking)
                .containsExactly(Checks.SIZE_CHANGE, false, false);

        Manifest incomplete = new Manifest(D2, List.of(entry(SourceFile.REGISTER, 0)));
        CheckResult files = Checks.rawFiles(incomplete, Optional.empty(), T).getFirst();
        assertThat(files.passed()).isFalse();
        assertThat(files.blocking()).isTrue();
        assertThat((List<?>) files.metadata().get("missing_or_empty")).hasSize(SourceFile.values().length);
    }

    @Test
    void schemaBlocksOnARemovedColumnOnly() {
        Map<String, List<String>> before = Map.of("register", List.of("regcode", "name", "type"));
        assertThat(Checks.schema(Map.of("register", List.of("regcode", "name", "type", "new_col")), Optional.of(before)).passed())
                .isTrue();
        CheckResult removed = Checks.schema(Map.of("register", List.of("regcode", "name")), Optional.of(before));
        assertThat(removed.passed()).isFalse();
        assertThat(removed.metadata().get("removed_columns")).isEqualTo(Map.of("register", List.of("type")));
        assertThat(Checks.schema(Map.of("register", List.of("regcode")), Optional.empty()).passed()).isTrue();
    }

    @Test
    void eventSpikeBlocksAboveTheHardShare() {
        assertThat(Checks.events(Map.of("NAME_CHANGED", 40_000L), 490_000, T).passed()).isTrue();
        CheckResult spike = Checks.events(Map.of("NAME_CHANGED", 60_000L), 490_000, T);
        assertThat(spike.passed()).isFalse();
        assertThat(spike.blocking()).isTrue();
        assertThat(spike.metadata().get("limit")).isEqualTo(49_000L);
        assertThat(Checks.events(Map.of("NAME_CHANGED", 400L), 10, T).passed()).as("minimum count").isTrue();
    }

    @Test
    void reportWarningsBecomeChecksAndOnlyDropShareAndAnEmptyRegisterBlock() {
        QualityReport report = report(List.of(new QualityReport.Warning("drop_share", "officers: 9 of 100 rows dropped"),
                new QualityReport.Warning("unmapped_status", "1 unmapped")));
        List<CheckResult> checks = Checks.report(report);
        assertThat(checks).extracting(CheckResult::name).containsExactly(Checks.DROP_SHARE, Checks.REGISTER_NOT_EMPTY,
                Checks.ORPHAN_ROLES, Checks.UNMAPPED_VALUES, Checks.ROW_CHANGE, Checks.DUPLICATE_REGCODES, Checks.EVENT_VOLUME);
        assertThat(checks).filteredOn(c -> !c.passed()).extracting(CheckResult::name)
                .containsExactly(Checks.DROP_SHARE, Checks.UNMAPPED_VALUES);
        assertThat(checks).filteredOn(CheckResult::blocking).extracting(CheckResult::name)
                .containsExactly(Checks.DROP_SHARE, Checks.REGISTER_NOT_EMPTY);
    }

    @Test
    void indexCountsAreComparedForTheLatestCaptureOnly() {
        assertThat(Checks.indexCounts(D2, D2, 10, Optional.of(10L)).passed()).isTrue();
        assertThat(Checks.indexCounts(D2, D2, 10, Optional.of(11L)).passed()).isFalse();
        CheckResult old = Checks.indexCounts(D1, D2, 10, Optional.of(11L));
        assertThat(old.passed()).isTrue();
        assertThat(old.metadata().get("compared")).isEqualTo(false);
    }

    @Test
    void storageWarnsAboveTheLimit() {
        Thresholds small = new Thresholds(0.01, 0.05, 0.01, 500, 100, 0.10);
        assertThat(Checks.storage(Map.of("lv-raw", 60L, "lv-lake", 40L), small).passed()).isTrue();
        CheckResult full = Checks.storage(Map.of("lv-raw", 60L, "lv-lake", 41L), small);
        assertThat(full.passed()).isFalse();
        assertThat(full.blocking()).isFalse();
    }

    private static Manifest manifest(LocalDate date, long size) {
        return new Manifest(date, Arrays.stream(SourceFile.values()).map(f -> entry(f, size)).toList());
    }

    private static ManifestEntry entry(SourceFile file, long size) {
        return new ManifestEntry(file.csvName(), URI.create("https://data.gov.lv/" + file.csvName()), "0".repeat(64), size,
                Instant.parse("2026-11-02T05:00:00Z"), Optional.empty());
    }

    private static QualityReport report(List<QualityReport.Warning> warnings) {
        QualityReport ok = ReportBuilder.failed("run", Optional.empty(), D2, List.of(), Instant.parse("2026-11-09T05:00:00Z"));
        return new QualityReport(ok.schemaVersion(), ok.runId(), ok.dagsterRunId(), D2, Optional.of(D1), Optional.of(7L),
                ok.startedAt(), ok.finishedAt(), QualityReport.Status.WARN, List.of(), warnings, List.of(), ok.snapshot(),
                Map.of(), Map.of());
    }
}
