package lvregister.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lvregister.diff.DiffStep;
import lvregister.diff.LakeCaptures;
import lvregister.ingest.Manifest;
import lvregister.ingest.RawLayout;
import lvregister.ingest.SourceFile;
import lvregister.lake.CaptureCounts;
import lvregister.lake.DuckDb;
import lvregister.lake.LakeLayout;
import lvregister.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code quality} step: reads what download, lake and diff wrote for a capture and writes
 * {@code reports/date=D/report.json}. If an input is missing it writes a FAILED report and
 * fails the step. Status WARN does not fail the step: Dagster checks decide what blocks.
 */
public final class QualityStep {

    private static final Logger LOG = LoggerFactory.getLogger(QualityStep.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DuckDb db;
    private final ObjectStore raw;
    private final ObjectStore lake;
    private final Map<String, ObjectStore> buckets;
    private final Thresholds thresholds;
    private final Clock clock;

    /** {@code buckets}: name to store, for the storage figures (raw, lake, marts). */
    public QualityStep(DuckDb db, ObjectStore raw, ObjectStore lake, Map<String, ObjectStore> buckets,
            Thresholds thresholds, Clock clock) {
        this.db = db;
        this.raw = raw;
        this.lake = lake;
        this.buckets = Map.copyOf(buckets);
        this.thresholds = thresholds;
        this.clock = clock;
    }

    public QualityReport run(LocalDate date, String runId, Optional<String> dagsterRunId) throws IOException, SQLException {
        List<String> errors = missingInputs(date);
        if (!errors.isEmpty()) {
            QualityReport failed = ReportBuilder.failed(runId, dagsterRunId, date, errors, clock.instant());
            lake.putAtomically(QualityReport.key(date), failed.toJson());
            throw new IllegalStateException("quality report for " + date + " is FAILED: " + errors);
        }
        Optional<LocalDate> previous = LakeCaptures.previous(LakeCaptures.dates(lake), date);
        Map<SourceFile, Long> rowsPrevious = new EnumMap<>(SourceFile.class);
        if (previous.isPresent()) {
            rowsPrevious.putAll(rowsRead(previous.get()));
        }
        Map<String, Long> bucketBytes = new TreeMap<>();
        for (var bucket : buckets.entrySet()) {
            bucketBytes.put(bucket.getKey(), bucket.getValue().totalSize(""));
        }
        ReportBuilder.Inputs inputs = new ReportBuilder.Inputs(runId, dagsterRunId, date, previous,
                Manifest.fromJson(raw.read(RawLayout.manifestKey(date))), rowsRead(date), rowsPrevious, missingRequired(date),
                CaptureCounts.fromJson(lake.read(CaptureCounts.key(date))), eventsByType(date), bucketBytes, clock.instant());
        QualityReport report = ReportBuilder.build(inputs, thresholds);
        lake.putAtomically(QualityReport.key(date), report.toJson());
        LOG.info("quality {}: {} with {} warnings ({}), {} events", date, report.status(), report.warnings().size(),
                report.warnings().stream().map(QualityReport.Warning::code).distinct().collect(Collectors.joining(",")),
                report.eventsTotal());
        return report;
    }

    private List<String> missingInputs(LocalDate date) throws IOException {
        List<String> errors = new ArrayList<>();
        if (!raw.exists(RawLayout.manifestKey(date))) {
            errors.add("no manifest: download did not run");
        }
        for (SourceFile file : SourceFile.values()) {
            if (!lake.exists(LakeLayout.parquetKey(file, date))) {
                errors.add("no Parquet file for " + file.baseName() + ": lake did not run");
            }
        }
        if (!lake.exists(CaptureCounts.key(date)) || !lake.exists(DiffStep.eventsKey(date))) {
            errors.add("no normalize counts or events: diff did not run");
        }
        return errors;
    }

    private Map<SourceFile, Long> rowsRead(LocalDate date) throws SQLException, IOException {
        Map<SourceFile, Long> rows = new EnumMap<>(SourceFile.class);
        for (SourceFile file : SourceFile.values()) {
            String key = LakeLayout.parquetKey(file, date);
            if (lake.exists(key)) {
                rows.put(file, Long.parseLong(db.queryString("SELECT sum(num_rows) FROM parquet_file_metadata("
                        + DuckDb.literal(lake.location(key)) + ")").orElse("0")));
            }
        }
        return rows;
    }

    private Map<SourceFile, Map<String, Long>> missingRequired(LocalDate date) throws SQLException {
        Map<SourceFile, Map<String, Long>> missing = new EnumMap<>(SourceFile.class);
        for (var entry : RequiredColumns.BY_FILE.entrySet()) {
            String select = entry.getValue().stream()
                    .map(col -> "CAST(count(*) - count(" + DuckDb.identifier(col) + ") AS VARCHAR)")
                    .collect(Collectors.joining(" || ',' || "));
            String counts = db.queryString("SELECT " + select + " FROM read_parquet("
                    + DuckDb.literal(lake.location(LakeLayout.parquetKey(entry.getKey(), date))) + ", hive_partitioning = false)")
                    .orElseThrow();
            String[] values = counts.split(",");
            Map<String, Long> perColumn = new TreeMap<>();
            for (int i = 0; i < values.length; i++) {
                perColumn.put(entry.getValue().get(i), Long.parseLong(values[i]));
            }
            missing.put(entry.getKey(), perColumn);
        }
        return missing;
    }

    private Map<String, Long> eventsByType(LocalDate date) throws IOException {
        String lines = new String(lake.read(DiffStep.eventsKey(date)), StandardCharsets.UTF_8);
        return lines.lines().filter(l -> !l.isBlank())
                .map(l -> JSON.readTree(l).path("type").asString())
                .collect(Collectors.groupingBy(t -> t, TreeMap::new, Collectors.counting()));
    }
}
