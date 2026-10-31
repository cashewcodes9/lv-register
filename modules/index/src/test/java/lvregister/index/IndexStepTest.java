package lvregister.index;

import static lvregister.diff.SyntheticCaptures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lvregister.diff.DiffStep;
import lvregister.lake.DuckDb;
import lvregister.lake.DuckDbSettings;
import lvregister.lake.LakeReader;
import lvregister.lake.SnapshotLoader;
import lvregister.quality.QualityStep;
import lvregister.quality.Thresholds;
import lvregister.serving.CompanyView;
import lvregister.serving.EventView;
import lvregister.serving.RunView;
import lvregister.serving.ServingWriter;
import lvregister.storage.LocalDirectoryObjectStore;
import lvregister.storage.ObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexStepTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 9);

    /** Records what the step writes, in order. */
    static final class RecordingWriter implements ServingWriter {
        final List<String> calls = new ArrayList<>();
        final List<CompanyView> companies = new ArrayList<>();
        final List<EventView> events = new ArrayList<>();
        final List<RunView> runs = new ArrayList<>();

        @Override
        public void prepare() {
            calls.add("prepare");
        }

        @Override
        public Upserted upsertCompanies(Stream<CompanyView> stream) {
            calls.add("companies");
            stream.forEach(companies::add);
            return new Upserted(companies.size(), 0);
        }

        @Override
        public Upserted upsertEvents(List<EventView> list) {
            calls.add("events");
            events.addAll(list);
            return new Upserted(list.size(), 0);
        }

        @Override
        public void upsertRun(RunView run) {
            calls.add("run");
            runs.add(run);
        }
    }

    @TempDir
    Path tmp;

    private ObjectStore raw;
    private ObjectStore lake;
    private DuckDb db;
    private SnapshotLoader loader;
    private RecordingWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        raw = new LocalDirectoryObjectStore(tmp.resolve("raw"));
        lake = new LocalDirectoryObjectStore(tmp.resolve("lake"));
        db = DuckDb.open(new DuckDbSettings("256MB", 1, tmp.resolve("duckdb"), Optional.empty()), Optional.empty());
        loader = new SnapshotLoader(new LakeReader(db, lake));
        writer = new RecordingWriter();
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    private void pipeline(LocalDate date, String register, String officers) throws Exception {
        capture(raw, lake, db, tmp.resolve("work"), date, files(register, officers));
        new DiffStep(lake, loader).run(date);
        new QualityStep(db, raw, lake, Map.of("lv-lake", lake), Thresholds.defaults(), Clock.systemUTC())
                .run(date, "run-" + date, Optional.empty());
    }

    @Test
    void writesCompaniesEventsAndTheRunLast() throws Exception {
        pipeline(D1, COMPANY_A + COMPANY_B, OFFICER_A + OFFICER_B);
        pipeline(D2, COMPANY_A.replace("Alfa", "Alfa Plus") + COMPANY_B, OFFICER_A);

        IndexStep.Result result = new IndexStep(lake, loader, writer).run(D2);

        assertThat(writer.calls).containsExactly("prepare", "companies", "events", "run");
        assertThat(writer.companies).extracting(CompanyView::regNumber).containsExactlyInAnyOrder("40003000001", "40003000002");
        CompanyView alfa = writer.companies.stream().filter(c -> c.regNumber().equals("40003000001")).findFirst().orElseThrow();
        assertThat(alfa.name()).isEqualTo("SIA \"Alfa Plus\"");
        assertThat(alfa.asOf()).isEqualTo(D2);
        assertThat(alfa.roles()).extracting(CompanyView.RoleView::kind).contains("officer", "shareholder", "beneficial_owner");
        assertThat(writer.events).extracting(EventView::type).containsExactlyInAnyOrder("NAME_CHANGED", "MANAGEMENT_CHANGED");
        String line = new String(lake.read(DiffStep.eventsKey(D2)), StandardCharsets.UTF_8).lines()
                .filter(l -> l.contains("NAME_CHANGED")).findFirst().orElseThrow();
        assertThat(line).contains("\"payload\":" + writer.events.stream().filter(e -> e.type().equals("NAME_CHANGED"))
                .findFirst().orElseThrow().payloadJson());
        assertThat(writer.runs).singleElement().satisfies(run -> {
            assertThat(run.runId()).isEqualTo("run-2026-11-09");
            assertThat(run.captureDate()).isEqualTo(D2);
            assertThat(run.reportJson()).contains("\"captureDate\" : \"2026-11-09\"");
        });
        assertThat(result.companies().written()).isEqualTo(2);
    }

    @Test
    void refusesWithoutAQualityReport() throws Exception {
        capture(raw, lake, db, tmp.resolve("work"), D1, files(COMPANY_A, OFFICER_A));

        assertThatIllegalStateException().isThrownBy(() -> new IndexStep(lake, loader, writer).run(D1))
                .withMessageContaining("run quality first");
        assertThat(writer.calls).isEmpty();
    }
}
