package lvregister.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lvregister.domain.Company;
import lvregister.domain.CompanyStatus;
import lvregister.lake.DuckDb;
import lvregister.lake.DuckDbSettings;
import lvregister.lake.LakeReader;
import lvregister.lake.SnapshotLoader;
import lvregister.storage.S3ObjectStore;
import lvregister.storage.S3Settings;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Manual check of the normalizers on a real capture in the lake. Skipped unless
 * {@code LV_REAL_CAPTURE_DATE} is set; run it in the build container on the compose network:
 * {@code LV_REAL_CAPTURE_DATE=2026-10-31 LV_TEST_HEAP=1600m DUCKDB_MEMORY_LIMIT=256MB
 * make gradle ARGS=":cli:test --tests *RealCaptureCheck"}. Prints aggregate counts only.
 */
class RealCaptureCheck {

    @Test
    void normalizesARealCapture() throws Exception {
        String date = System.getenv("LV_REAL_CAPTURE_DATE");
        assumeTrue(date != null && !date.isBlank(), "LV_REAL_CAPTURE_DATE not set");
        S3Settings settings = S3Settings.fromEnvironment(Map.of("S3_ENDPOINT", "http://lake:8333",
                "S3_ACCESS_KEY", "lvdev", "S3_SECRET_KEY", "lvdev-local-only-secret"));
        try (S3Client s3 = S3ObjectStore.client(settings);
                DuckDb db = DuckDb.open(DuckDbSettings.fromEnvironment(System.getenv()), Optional.of(settings))) {
            SnapshotLoader.Loaded loaded = new SnapshotLoader(new LakeReader(db, new S3ObjectStore(s3, "lv-lake")))
                    .load(LocalDate.parse(date));

            loaded.counts().forEach((file, c) -> System.out.printf("%-34s kept %8d  dropped %s  flagged %s%n",
                    file.baseName(), c.kept(), c.droppedByRule(), c.flaggedByRule()));
            var snapshot = loaded.snapshot();
            System.out.printf("snapshot: %d companies, %d companies with roles, %d orphan roles, %d duplicate roles%n",
                    snapshot.companies().size(), snapshot.roles().size(), loaded.orphanRoles(), loaded.duplicateRoles());
            Map<CompanyStatus, Long> statuses = snapshot.companies().values().stream()
                    .collect(Collectors.groupingBy(Company::status, TreeMap::new, Collectors.counting()));
            System.out.println("status: " + statuses);
            Runtime rt = Runtime.getRuntime();
            System.gc();
            System.out.printf("heap used after load: %d MB%n", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));

            assertThat(snapshot.companies()).isNotEmpty();
        }
    }
}
