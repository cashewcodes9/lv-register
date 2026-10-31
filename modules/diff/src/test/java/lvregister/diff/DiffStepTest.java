package lvregister.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lvregister.ingest.Manifest;
import lvregister.ingest.ManifestEntry;
import lvregister.ingest.RawLayout;
import lvregister.ingest.SourceFile;
import lvregister.lake.CaptureCounts;
import lvregister.lake.DuckDb;
import lvregister.lake.DuckDbSettings;
import lvregister.lake.LakeReader;
import lvregister.lake.LakeWriter;
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
        Manifest manifest = Manifest.empty(date);
        for (SourceFile file : SourceFile.values()) {
            byte[] bytes = files.get(file).getBytes(StandardCharsets.UTF_8);
            raw.put(RawLayout.csvKey(date, file), bytes);
            manifest = manifest.with(new ManifestEntry(file.csvName(), URI.create("https://example.org/" + file.csvName()),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length,
                    Instant.parse("2026-11-02T05:00:00Z"), Optional.empty()));
        }
        raw.put(RawLayout.manifestKey(date), manifest.toJson());
        new LakeWriter(db, raw, lake, tmp.resolve("work")).run(date);
    }

    /** Minimal files with the real column names; one row each, so DuckDB sees the columns. */
    private static Map<SourceFile, String> files(String register, String officers) {
        Map<SourceFile, String> f = new EnumMap<>(SourceFile.class);
        f.put(SourceFile.REGISTER, "regcode;name;type;registered;terminated;closed;address;index;addressid;atvk\n" + register);
        f.put(SourceFile.OFFICERS, "id;at_legal_entity_registration_number;entity_type;position;governing_body;name;"
                + "latvian_identity_number_masked;birth_date;legal_entity_registration_number;registered_on\n" + officers);
        String holder = "entity_type;name;latvian_identity_number_masked;birth_date;legal_entity_registration_number";
        f.put(SourceFile.MEMBERS, "id;at_legal_entity_registration_number;" + holder
                + ";number_of_shares;share_nominal_value;share_currency;date_from\n"
                + "m1;40003000001;FOREIGN_ENTITY;Owner Ltd;;;;100;1;EUR;2015-01-01\n");
        f.put(SourceFile.MEMBERS_JOINT_OWNERS, "id;member_id;" + holder + "\nj1;none;FOREIGN_ENTITY;X Ltd;;;\n");
        f.put(SourceFile.STOCKHOLDERS, "id;at_legal_entity_registration_number;" + holder
                + ";number_of_shares;share_nominal_value;share_currency;date_from;depository_registration_number;depository_name\n"
                + "s1;40003000099;FOREIGN_ENTITY;Y Ltd;;;;1;;;;;\n");
        f.put(SourceFile.STOCKHOLDERS_JOINT_OWNERS, "id;member_id;" + holder + "\nj2;none;FOREIGN_ENTITY;Z Ltd;;;\n");
        f.put(SourceFile.BENEFICIAL_OWNERS, "id;legal_entity_registration_number;forename;surname;"
                + "latvian_identity_number_masked;birth_date;nationality;residence;registered_on\n"
                + "b1;40003000001;Test;Person;010101-*****;;LV;LV;2018-01-01\n");
        f.put(SourceFile.EQUITY_CAPITALS, "id;legal_entity_registration_number;equity_capital_type;amount;currency\n"
                + "e1;40003000001;PAID_UP_EQUITY;2800.00;EUR\n");
        f.put(SourceFile.LIQUIDATIONS, "id;legal_entity_registration_number;liquidation_type;date_from\nl1;40003000099;LIQUIDATION;2026-01-01\n");
        f.put(SourceFile.INSOLVENCY_LEGAL_PERSON_PROCEEDING,
                "proceeding_id;debtor_registration_number;proceeding_form;proceeding_started_on;proceeding_ended_on\n"
                        + "p1;40003000099;INSOLVENCY;2020-01-01;2021-01-01\n");
        f.put(SourceFile.REORGANIZATIONS, "id;reorganization_type;source_entity_regcode;final_entity_regcode;registered\n"
                + "r1;ACQUISITION;40003000098;40003000099;2015-01-01\n");
        return f;
    }

    private static final String A = "40003000001;\"SIA \"\"Alfa\"\"\";SIA;2010-01-02;; ;Rīga, Testa iela 1;1050;100;0001000\n";
    private static final String B = "40003000002;\"SIA \"\"Beta\"\"\";SIA;2011-01-02;; ;Rīga, Testa iela 2;1050;101;0001000\n";
    private static final String OFFICER_A = "o1;40003000001;NATURAL_PERSON;BOARD_MEMBER;EXECUTIVE_BOARD;Test Person;010101-*****;;;2020-01-01\n";
    private static final String OFFICER_B = "o2;40003000002;FOREIGN_ENTITY;BOARD_MEMBER;;Board Ltd;;;;2021-01-01\n";

    @Test
    void firstCaptureIsTheBaseline() throws Exception {
        capture(D1, files(A + B, OFFICER_A + OFFICER_B));

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
        capture(D1, files(A + B, OFFICER_A + OFFICER_B));
        String a2 = A.replace("Alfa", "Alfa Plus");
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
        capture(D1, files(A + B, OFFICER_A));
        capture(D2, files(A.replace("Alfa", "Alfa Plus") + B, OFFICER_A + OFFICER_B));
        step.run(D2);
        byte[] first = lake.read(DiffStep.eventsKey(D2));

        step.run(D2);

        assertThat(lake.read(DiffStep.eventsKey(D2))).isEqualTo(first).isNotEmpty();
    }

    @Test
    void comparesWithTheLatestEarlierCaptureAcrossGaps() throws Exception {
        capture(D1, files(A, OFFICER_A));
        capture(D2.plusDays(14), files(A.replace("Alfa", "Alfa Plus"), OFFICER_A));

        assertThat(step.run(D2.plusDays(14)).previous()).contains(D1);
    }

    @Test
    void failsWhenTheCaptureIsNotInTheLake() {
        assertThatIllegalStateException().isThrownBy(() -> step.run(D1)).withMessageContaining("run lake first");
    }
}
