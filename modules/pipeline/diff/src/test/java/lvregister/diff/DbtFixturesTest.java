package lvregister.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lvregister.ingest.SourceFile;
import lvregister.lake.DuckDb;
import lvregister.lake.DuckDbSettings;
import lvregister.lake.LakeLayout;
import lvregister.lake.LakeReader;
import lvregister.lake.SnapshotLoader;
import lvregister.storage.LocalDirectoryObjectStore;
import lvregister.storage.ObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generates the lake fixtures of the dbt project (transform/fixtures/lake) with the real Java
 * lake and diff steps, and fails when the committed fixtures are out of date. Regenerate with
 * {@code LV_WRITE_DBT_FIXTURES=1 make gradle ARGS=":diff:test --tests *DbtFixturesTest"}.
 */
class DbtFixturesTest {

    private static final Path COMMITTED = Path.of("../../../transform/fixtures/lake");

    @TempDir
    Path tmp;

    @Test
    void committedFixturesMatchTheJavaPipeline() throws Exception {
        ObjectStore raw = new LocalDirectoryObjectStore(tmp.resolve("raw"));
        Path lakeDir = tmp.resolve("lake");
        ObjectStore lake = new LocalDirectoryObjectStore(lakeDir);
        Map<LocalDate, Map<String, Long>> eventsByCapture = new TreeMap<>();
        try (DuckDb db = DuckDb.open(new DuckDbSettings("256MB", 1, tmp.resolve("duckdb"), Optional.empty()), Optional.empty())) {
            DiffStep diff = new DiffStep(lake, new SnapshotLoader(new LakeReader(db, lake)));
            for (LocalDate date : DbtFixtures.DATES) {
                SyntheticCaptures.capture(raw, lake, db, tmp.resolve("work"), date, DbtFixtures.capture(date));
                eventsByCapture.put(date, diff.run(date).eventsByType());
            }

            // what the fixtures are meant to show (see DbtFixtures)
            assertThat(eventsByCapture.get(DbtFixtures.D1)).isEmpty();
            assertThat(eventsByCapture.get(DbtFixtures.D2)).containsOnly(Map.entry("ADDRESS_CHANGED", 1L),
                    Map.entry("LEGAL_FORM_CHANGED", 1L), Map.entry("MANAGEMENT_CHANGED", 1L), Map.entry("NAME_CHANGED", 1L),
                    Map.entry("OWNERSHIP_CHANGED", 1L), Map.entry("REGISTERED", 1L), Map.entry("STATUS_CHANGED", 1L),
                    Map.entry("TERMINATED", 1L));
            assertThat(eventsByCapture.get(DbtFixtures.D3)).containsOnly(Map.entry("MISSING_FROM_SNAPSHOT", 1L),
                    Map.entry("OWNERSHIP_CHANGED", 1L), Map.entry("STATUS_CHANGED", 1L), Map.entry("TERMINATED", 1L));

            if ("1".equals(System.getenv("LV_WRITE_DBT_FIXTURES"))) {
                write(lake, lakeDir);
            }
            assertThat(Files.isDirectory(COMMITTED)).as("committed fixtures exist; generate them with LV_WRITE_DBT_FIXTURES=1").isTrue();
            for (LocalDate date : DbtFixtures.DATES) {
                String key = DiffStep.eventsKey(date);
                assertThat(Files.readString(COMMITTED.resolve(key))).as(key).isEqualTo(new String(lake.read(key), StandardCharsets.UTF_8));
                for (SourceFile file : SourceFile.values()) {
                    String parquet = LakeLayout.parquetKey(file, date);
                    String committed = DuckDb.literal(COMMITTED.resolve(parquet).toAbsolutePath().toString());
                    String fresh = DuckDb.literal(lake.location(parquet));
                    assertThat(db.queryString("SELECT count(*) FROM ((SELECT * FROM read_parquet(" + committed + ", hive_partitioning = false)"
                            + " EXCEPT ALL SELECT * FROM read_parquet(" + fresh + ", hive_partitioning = false)) UNION ALL"
                            + " (SELECT * FROM read_parquet(" + fresh + ", hive_partitioning = false)"
                            + " EXCEPT ALL SELECT * FROM read_parquet(" + committed + ", hive_partitioning = false)))"))
                            .as(parquet).contains("0");
                }
            }
        }
    }

    /** Copies the Parquet files and events of the generated lake into transform/fixtures/lake. */
    private static void write(ObjectStore lake, Path lakeDir) throws Exception {
        List<String> keys = lake.list("").stream()
                .filter(k -> k.endsWith(".parquet") || k.endsWith("events.jsonl"))
                .collect(Collectors.toList());
        for (String key : keys) {
            Path target = COMMITTED.resolve(key);
            Files.createDirectories(target.getParent());
            Files.copy(lakeDir.resolve(key), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
