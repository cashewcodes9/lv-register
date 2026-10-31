package lvregister.quality;

import static lvregister.diff.SyntheticCaptures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import lvregister.diff.DiffStep;
import lvregister.lake.DuckDb;
import lvregister.lake.DuckDbSettings;
import lvregister.lake.LakeReader;
import lvregister.lake.SnapshotLoader;
import lvregister.storage.LocalDirectoryObjectStore;
import lvregister.storage.ObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QualityStepTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 9);

    @TempDir
    Path tmp;

    private ObjectStore raw;
    private ObjectStore lake;
    private DuckDb db;
    private QualityStep step;

    @BeforeEach
    void setUp() throws Exception {
        raw = new LocalDirectoryObjectStore(tmp.resolve("raw"));
        lake = new LocalDirectoryObjectStore(tmp.resolve("lake"));
        db = DuckDb.open(new DuckDbSettings("256MB", 1, tmp.resolve("duckdb"), Optional.empty()), Optional.empty());
        step = new QualityStep(db, raw, lake, Map.of("lv-raw", raw, "lv-lake", lake), Thresholds.defaults(),
                Clock.fixed(Instant.parse("2026-11-09T05:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    private void captureAndDiff(LocalDate date, String register, String officers) throws Exception {
        capture(raw, lake, db, tmp.resolve("work"), date, files(register, officers));
        new DiffStep(lake, new SnapshotLoader(new LakeReader(db, lake))).run(date);
    }

    @Test
    void reportOfASecondCapture() throws Exception {
        captureAndDiff(D1, COMPANY_A + COMPANY_B, OFFICER_A + OFFICER_B);
        captureAndDiff(D2, COMPANY_A.replace("Alfa", "Alfa Plus") + COMPANY_B + "40003000003;X;SIA;2026-11-04;; ;;;;\n",
                OFFICER_A);

        QualityReport report = step.run(D2, "run-7", Optional.empty());

        assertThat(report).isEqualTo(QualityReport.fromJson(lake.read(QualityReport.key(D2))));
        assertThat(report.previousCapture()).contains(D1);
        assertThat(report.daysSincePrevious()).contains(7L);
        assertThat(report.runId()).isEqualTo("run-7");
        assertThat(report.eventsByType()).containsOnly(Map.entry("MANAGEMENT_CHANGED", 1L), Map.entry("NAME_CHANGED", 1L),
                Map.entry("REGISTERED", 1L));
        assertThat(report.snapshot().companies()).isEqualTo(3);
        assertThat(report.snapshot().orphanRoles()).isEqualTo(1);
        QualityReport.FileReport register = report.files().getFirst();
        assertThat(register.file()).isEqualTo("register");
        assertThat(register.rowsRead()).isEqualTo(3);
        assertThat(register.rowsReadPrevious()).contains(2L);
        assertThat(register.rowsKept()).isEqualTo(3);
        assertThat(register.missingRequired()).containsOnly(Map.entry("regcode", 0L), Map.entry("name", 0L), Map.entry("type", 0L));
        assertThat(report.bucketBytes()).containsOnlyKeys("lv-raw", "lv-lake");
        assertThat(report.bucketBytes().get("lv-lake")).isPositive();
        assertThat(report.warnings()).extracting(QualityReport.Warning::code).contains("orphan_roles", "row_change");
        assertThat(new String(report.toJson(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(PERSON_NAMES.getFirst());
    }

    @Test
    void missingInputsGiveAFailedReport() throws Exception {
        capture(raw, lake, db, tmp.resolve("work"), D1, files(COMPANY_A, OFFICER_A));

        assertThatIllegalStateException().isThrownBy(() -> step.run(D1, "run-8", Optional.empty()))
                .withMessageContaining("diff did not run");
        QualityReport failed = QualityReport.fromJson(lake.read(QualityReport.key(D1)));
        assertThat(failed.status()).isEqualTo(QualityReport.Status.FAILED);
        assertThat(failed.errors()).containsExactly("no normalize counts or events: diff did not run");
    }
}
