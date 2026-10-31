package lvregister.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import lvregister.domain.Cleaned;
import lvregister.domain.Company;
import lvregister.domain.CompanyStatus;
import lvregister.domain.Role;
import lvregister.ingest.SourceFile;
import lvregister.lake.DuckDb;
import lvregister.lake.DuckDbSettings;
import lvregister.lake.LakeReader;
import lvregister.normalize.Liquidation;
import lvregister.normalize.Proceeding;
import lvregister.normalize.RegisterNormalizer;
import lvregister.normalize.RoleNormalizers;
import lvregister.normalize.Row;
import lvregister.normalize.Snapshots;
import lvregister.normalize.StatusNormalizers;
import lvregister.storage.S3ObjectStore;
import lvregister.storage.S3Settings;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Manual check of the normalizers on a real capture in the lake. Skipped unless
 * {@code LV_REAL_CAPTURE_DATE} is set; run it in the build container on the compose network:
 * {@code make gradle ARGS=":cli:test --tests *RealCaptureCheck" LV_REAL_CAPTURE_DATE=2026-10-31}.
 * Prints aggregate counts only, never person data.
 */
class RealCaptureCheck {

    @Test
    void normalizesARealCapture() throws Exception {
        String date = System.getenv("LV_REAL_CAPTURE_DATE");
        assumeTrue(date != null && !date.isBlank(), "LV_REAL_CAPTURE_DATE not set");
        LocalDate day = LocalDate.parse(date);
        Map<String, String> env = Map.of("S3_ENDPOINT", "http://lake:8333", "S3_ACCESS_KEY", "lvdev",
                "S3_SECRET_KEY", "lvdev-local-only-secret");
        S3Settings settings = S3Settings.fromEnvironment(env);
        try (S3Client s3 = S3ObjectStore.client(settings);
                DuckDb db = DuckDb.open(DuckDbSettings.fromEnvironment(System.getenv()), Optional.of(settings))) {
            LakeReader lake = new LakeReader(db, new S3ObjectStore(s3, "lv-lake"));
            Read read = new Read(lake, day);

            Cleaned<Liquidation> liquidations = read.file(SourceFile.LIQUIDATIONS, StatusNormalizers.LIQUIDATION_COLUMNS,
                    StatusNormalizers::liquidations);
            Cleaned<Proceeding> proceedings = read.file(SourceFile.INSOLVENCY_LEGAL_PERSON_PROCEEDING,
                    StatusNormalizers.INSOLVENCY_COLUMNS, StatusNormalizers::proceedings);
            var equity = read.file(SourceFile.EQUITY_CAPITALS, StatusNormalizers.EQUITY_COLUMNS, StatusNormalizers::equityCapitals);
            var reorganizations = read.file(SourceFile.REORGANIZATIONS, StatusNormalizers.REORGANIZATION_COLUMNS,
                    StatusNormalizers::reorganizations);
            Cleaned<Company> companies = read.file(SourceFile.REGISTER, RegisterNormalizer.COLUMNS,
                    rows -> RegisterNormalizer.normalize(rows,
                            StatusNormalizers.openProceedings(liquidations.rows(), proceedings.rows()),
                            StatusNormalizers.capital(equity.rows())));
            Cleaned<Role.Officer> officers = read.file(SourceFile.OFFICERS, RoleNormalizers.OFFICER_COLUMNS, RoleNormalizers::officers);
            Cleaned<Role.Shareholder> members;
            try (Stream<Row> joint = read.rows(SourceFile.MEMBERS_JOINT_OWNERS, RoleNormalizers.JOINT_OWNER_COLUMNS)) {
                members = read.file(SourceFile.MEMBERS, RoleNormalizers.MEMBER_COLUMNS, rows -> RoleNormalizers.members(rows, joint));
            }
            Cleaned<Role.Shareholder> stockholders;
            try (Stream<Row> joint = read.rows(SourceFile.STOCKHOLDERS_JOINT_OWNERS, RoleNormalizers.JOINT_OWNER_COLUMNS)) {
                stockholders = read.file(SourceFile.STOCKHOLDERS, RoleNormalizers.STOCKHOLDER_COLUMNS,
                        rows -> RoleNormalizers.stockholders(rows, joint));
            }
            Cleaned<Role.BeneficialOwner> owners = read.file(SourceFile.BENEFICIAL_OWNERS, RoleNormalizers.BENEFICIAL_OWNER_COLUMNS,
                    RoleNormalizers::beneficialOwners);

            report("liquidations", liquidations);
            report("insolvency", proceedings);
            report("equity", equity);
            report("reorganizations", reorganizations);
            report("register", companies);
            report("officers", officers);
            report("members", members);
            report("stockholders", stockholders);
            report("beneficial_owners", owners);

            Snapshots.Assembly assembly = Snapshots.assemble(day, companies.rows(),
                    List.of(officers.rows(), members.rows(), stockholders.rows(), owners.rows()));
            System.out.printf("snapshot: %d companies, %d companies with roles, %d orphan roles, %d duplicate roles%n",
                    assembly.snapshot().companies().size(), assembly.snapshot().roles().size(),
                    assembly.orphanRoles(), assembly.duplicateRoles());
            Map<CompanyStatus, Long> statuses = companies.rows().stream()
                    .collect(java.util.stream.Collectors.groupingBy(Company::status, java.util.TreeMap::new,
                            java.util.stream.Collectors.counting()));
            System.out.println("status: " + statuses);
            System.out.println("with capital: " + companies.rows().stream().filter(c -> c.capital().isPresent()).count());

            assertThat(companies.rows()).isNotEmpty();
        }
    }

    private static void report(String name, Cleaned<?> cleaned) {
        System.out.printf("%-18s kept %8d  dropped %s  flagged %s%n", name, cleaned.rows().size(),
                cleaned.droppedByRule(), cleaned.flaggedByRule());
    }

    private record Read(LakeReader lake, LocalDate day) {
        Stream<Row> rows(SourceFile file, List<String> columns) throws Exception {
            return lake.rows(file, day, columns).map(Row::of);
        }

        <T> Cleaned<T> file(SourceFile file, List<String> columns, Function<Stream<Row>, Cleaned<T>> normalizer) throws Exception {
            try (Stream<Row> rows = rows(file, columns)) {
                return normalizer.apply(rows);
            }
        }
    }
}
