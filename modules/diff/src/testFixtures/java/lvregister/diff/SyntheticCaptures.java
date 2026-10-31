package lvregister.diff;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import lvregister.ingest.Manifest;
import lvregister.ingest.ManifestEntry;
import lvregister.ingest.RawLayout;
import lvregister.ingest.SourceFile;
import lvregister.lake.DuckDb;
import lvregister.lake.LakeWriter;
import lvregister.storage.ObjectStore;

/**
 * Synthetic UR captures with the real column names (one row per file at least, so DuckDB sees
 * the columns). Names and codes are invented; no real data.
 */
public final class SyntheticCaptures {

    public static final String COMPANY_A =
            "40003000001;\"SIA \"\"Alfa\"\"\";SIA;2010-01-02;; ;Rīga, Testa iela 1;1050;100;0001000\n";
    public static final String COMPANY_B =
            "40003000002;\"SIA \"\"Beta\"\"\";SIA;2011-01-02;; ;Rīga, Testa iela 2;1050;101;0001000\n";
    public static final String OFFICER_A =
            "o1;40003000001;NATURAL_PERSON;BOARD_MEMBER;EXECUTIVE_BOARD;Test Person;010101-*****;;;2020-01-01\n";
    public static final String OFFICER_B = "o2;40003000002;FOREIGN_ENTITY;BOARD_MEMBER;;Board Ltd;;;;2021-01-01\n";
    /** Person names used in the fixtures, for checks that they never reach logs. */
    public static final java.util.List<String> PERSON_NAMES = java.util.List.of("Test Person", "Other Person");

    private SyntheticCaptures() {}

    /** The 11 files of a capture with the given register and officer rows. */
    public static Map<SourceFile, String> files(String register, String officers) {
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

    /** Writes the raw files and manifest as download would, then runs the lake step. */
    public static void capture(ObjectStore raw, ObjectStore lake, DuckDb db, Path workDir, LocalDate date,
            Map<SourceFile, String> files) throws Exception {
        writeRaw(raw, date, files);
        new LakeWriter(db, raw, lake, workDir).run(date);
    }

    /** Only the raw files and manifest, as download would write them. */
    public static void writeRaw(ObjectStore raw, LocalDate date, Map<SourceFile, String> files) throws Exception {
        Manifest manifest = Manifest.empty(date);
        for (SourceFile file : SourceFile.values()) {
            byte[] bytes = files.get(file).getBytes(StandardCharsets.UTF_8);
            raw.put(RawLayout.csvKey(date, file), bytes);
            manifest = manifest.with(new ManifestEntry(file.csvName(), URI.create("https://example.org/" + file.csvName()),
                    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length,
                    Instant.parse("2026-11-02T05:00:00Z"), Optional.empty()));
        }
        raw.put(RawLayout.manifestKey(date), manifest.toJson());
    }
}
