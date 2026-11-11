package lvregister.lake;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import lvregister.domain.Cleaned;
import lvregister.domain.Company;
import lvregister.domain.Role;
import lvregister.domain.Snapshot;
import lvregister.ingest.SourceFile;
import lvregister.normalize.EquityCapital;
import lvregister.normalize.Liquidation;
import lvregister.normalize.Proceeding;
import lvregister.normalize.RegisterNormalizer;
import lvregister.normalize.RoleNormalizers;
import lvregister.normalize.Row;
import lvregister.normalize.Snapshots;
import lvregister.normalize.StatusNormalizers;

/**
 * Builds the {@link Snapshot} of one capture from the lake: reads each file through
 * {@link LakeReader}, runs the normalizers and assembles the result. The per-file counts are
 * returned with it for the quality report; the cleaned row lists are not kept.
 */
public final class SnapshotLoader {

    /** Counts of one normalized file. */
    public record FileCounts(long kept, Map<String, Long> droppedByRule, Map<String, Long> flaggedByRule) {
        static FileCounts of(Cleaned<?> cleaned) {
            return new FileCounts(cleaned.rows().size(), cleaned.droppedByRule(), cleaned.flaggedByRule());
        }
    }

    /** The snapshot with the counts per file and what assembly removed. */
    public record Loaded(Snapshot snapshot, Map<SourceFile, FileCounts> counts, long orphanRoles, long duplicateRoles) {}

    private final LakeReader lake;

    public SnapshotLoader(LakeReader lake) {
        this.lake = lake;
    }

    public Loaded load(LocalDate date) throws IOException, SQLException {
        Map<SourceFile, FileCounts> counts = new LinkedHashMap<>();
        Cleaned<Liquidation> liquidations = file(SourceFile.LIQUIDATIONS, date, StatusNormalizers.LIQUIDATION_COLUMNS,
                StatusNormalizers::liquidations, counts);
        Cleaned<Proceeding> proceedings = file(SourceFile.INSOLVENCY_LEGAL_PERSON_PROCEEDING, date,
                StatusNormalizers.INSOLVENCY_COLUMNS, StatusNormalizers::proceedings, counts);
        Cleaned<EquityCapital> equity = file(SourceFile.EQUITY_CAPITALS, date, StatusNormalizers.EQUITY_COLUMNS,
                StatusNormalizers::equityCapitals, counts);
        file(SourceFile.REORGANIZATIONS, date, StatusNormalizers.REORGANIZATION_COLUMNS, StatusNormalizers::reorganizations, counts);
        var open = StatusNormalizers.openProceedings(liquidations.rows(), proceedings.rows());
        var capital = StatusNormalizers.capital(equity.rows());
        Cleaned<Company> companies = file(SourceFile.REGISTER, date, RegisterNormalizer.COLUMNS,
                rows -> RegisterNormalizer.normalize(rows, open, capital), counts);

        List<List<? extends Role>> roles = new ArrayList<>();
        roles.add(file(SourceFile.OFFICERS, date, RoleNormalizers.OFFICER_COLUMNS, RoleNormalizers::officers, counts).rows());
        roles.add(shareholders(SourceFile.MEMBERS, SourceFile.MEMBERS_JOINT_OWNERS, RoleNormalizers.MEMBER_COLUMNS,
                date, RoleNormalizers::members, counts).rows());
        roles.add(shareholders(SourceFile.STOCKHOLDERS, SourceFile.STOCKHOLDERS_JOINT_OWNERS, RoleNormalizers.STOCKHOLDER_COLUMNS,
                date, RoleNormalizers::stockholders, counts).rows());
        roles.add(file(SourceFile.BENEFICIAL_OWNERS, date, RoleNormalizers.BENEFICIAL_OWNER_COLUMNS,
                RoleNormalizers::beneficialOwners, counts).rows());

        Snapshots.Assembly assembly = Snapshots.assemble(date, companies.rows(), roles);
        return new Loaded(assembly.snapshot(), counts, assembly.orphanRoles(), assembly.duplicateRoles());
    }

    private interface JointNormalizer {
        Cleaned<Role.Shareholder> apply(Stream<Row> rows, Stream<Row> jointOwners);
    }

    private Cleaned<Role.Shareholder> shareholders(SourceFile file, SourceFile jointFile, List<String> columns, LocalDate date,
            JointNormalizer normalizer, Map<SourceFile, FileCounts> counts) throws IOException, SQLException {
        try (Stream<Row> joint = rows(jointFile, date, RoleNormalizers.JOINT_OWNER_COLUMNS)) {
            return file(file, date, columns, rows -> normalizer.apply(rows, joint), counts);
        }
    }

    private <T> Cleaned<T> file(SourceFile file, LocalDate date, List<String> columns,
            Function<Stream<Row>, Cleaned<T>> normalizer, Map<SourceFile, FileCounts> counts) throws IOException, SQLException {
        try (Stream<Row> rows = rows(file, date, columns)) {
            Cleaned<T> cleaned = normalizer.apply(rows);
            counts.put(file, FileCounts.of(cleaned));
            return cleaned;
        }
    }

    private Stream<Row> rows(SourceFile file, LocalDate date, List<String> columns) throws IOException, SQLException {
        return lake.rows(file, date, columns).map(Row::of);
    }
}
