package lvregister.diff;

import static lvregister.diff.SyntheticCaptures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lvregister.ingest.SourceFile;
import lvregister.lake.CaptureCounts;
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

/** download output → lake → normalize → diff → events.jsonl, on synthetic CSVs. */
class DiffStepTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 9);

    @TempDir
    Path tmp;

    private ObjectStore raw;
    private ObjectStore lake;
    private DuckDb db;
    private DiffStep step;

    @BeforeEach
    void setUp() throws Exception {
        raw = new LocalDirectoryObjectStore(tmp.resolve("raw"));
        lake = new LocalDirectoryObjectStore(tmp.resolve("lake"));
        db = DuckDb.open(new DuckDbSettings("256MB", 1, tmp.resolve("duckdb"), Optional.empty()), Optional.empty());
        step = new DiffStep(lake, new SnapshotLoader(new LakeReader(db, lake)));
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    private void capture(LocalDate date, Map<SourceFile, String> files) throws Exception {
        SyntheticCaptures.capture(raw, lake, db, tmp.resolve("work"), date, files);
    }

    @Test
    void firstCaptureIsTheBaseline() throws Exception {
        capture(D1, files(COMPANY_A + COMPANY_B, OFFICER_A + OFFICER_B));

        DiffStep.Result result = step.run(D1);

        assertThat(result.previous()).isEmpty();
        assertThat(lake.read(DiffStep.eventsKey(D1))).isEmpty();
        CaptureCounts counts = CaptureCounts.fromJson(lake.read(CaptureCounts.key(D1)));
        assertThat(counts.companies()).isEqualTo(2);
        assertThat(counts.files().get("register").kept()).isEqualTo(2);
        assertThat(counts.files().get("stockholders").kept()).isEqualTo(1);
        assertThat(counts.orphanRoles()).isEqualTo(1);
    }

    @Test
    void secondCaptureGivesTheEventsSortedById() throws Exception {
        capture(D1, files(COMPANY_A + COMPANY_B, OFFICER_A + OFFICER_B));
        String a2 = COMPANY_A.replace("Alfa", "Alfa Plus");
        String b2 = "40003000002;\"SIA \"\"Beta\"\"\";SIA;2011-01-02;2026-11-05;L;Rīga, Testa iela 2;1050;101;0001000\n";
        String c = "40003000003;\"SIA \"\"Gamma\"\"\";SIA;2026-11-04;; ;Rīga, Testa iela 3;1050;102;0001000\n";
        String newOfficer = "o3;40003000001;NATURAL_PERSON;CHAIR_OF_BOARD;EXECUTIVE_BOARD;Other Person;020202-*****;;;2026-11-03\n";
        capture(D2, files(a2 + b2 + c, OFFICER_A + newOfficer));

        DiffStep.Result result = step.run(D2);

        assertThat(result.previous()).contains(D1);
        assertThat(result.eventsByType()).containsOnly(Map.entry("MANAGEMENT_CHANGED", 1L), Map.entry("NAME_CHANGED", 1L),
                Map.entry("REGISTERED", 1L), Map.entry("TERMINATED", 1L));
        List<String> lines = new String(lake.read(DiffStep.eventsKey(D2)), StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(4);
        assertThat(lines.stream().map(l -> l.substring(l.indexOf("\"id\":\"") + 6, l.indexOf("\"id\":\"") + 70)).toList()).isSorted();
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("\"type\":\"TERMINATED\"", "\"terminatedOn\":\"2026-11-05\"",
                "\"regNumber\":\"40003000002\"", "\"previous\":\"2026-11-02\"", "\"current\":\"2026-11-09\""));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("\"new\":\"SIA \\\"Alfa Plus\\\"\""));
    }

    @Test
    void rerunningGivesByteIdenticalEvents() throws Exception {
        capture(D1, files(COMPANY_A + COMPANY_B, OFFICER_A));
        capture(D2, files(COMPANY_A.replace("Alfa", "Alfa Plus") + COMPANY_B, OFFICER_A + OFFICER_B));
        step.run(D2);
        byte[] first = lake.read(DiffStep.eventsKey(D2));

        step.run(D2);

        assertThat(lake.read(DiffStep.eventsKey(D2))).isEqualTo(first).isNotEmpty();
    }

    @Test
    void comparesWithTheLatestEarlierCaptureAcrossGaps() throws Exception {
        capture(D1, files(COMPANY_A, OFFICER_A));
        capture(D2.plusDays(14), files(COMPANY_A.replace("Alfa", "Alfa Plus"), OFFICER_A));

        assertThat(step.run(D2.plusDays(14)).previous()).contains(D1);
    }

    @Test
    void failsWhenTheCaptureIsNotInTheLake() {
        assertThatIllegalStateException().isThrownBy(() -> step.run(D1)).withMessageContaining("run lake first");
    }
}
