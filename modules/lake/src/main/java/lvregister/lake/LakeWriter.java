package lvregister.lake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lvregister.ingest.Manifest;
import lvregister.ingest.ManifestEntry;
import lvregister.ingest.RawLayout;
import lvregister.ingest.SourceFile;
import lvregister.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code lake} step. For one capture date, converts each raw CSV into Parquet with DuckDB:
 * every row and column, all as VARCHAR (types are applied in normalize, so registration numbers
 * keep their leading zeros), ZSTD-compressed, with the source file, sha256 and size in the
 * Parquet key-value metadata.
 *
 * <p>Idempotent: if the capture's Parquet file already carries the source sha256 from the
 * manifest, it is left alone. DuckDB writes the Parquet file locally; it is uploaded to a
 * temporary key and copied into place.
 */
public final class LakeWriter {

    private static final Logger LOG = LoggerFactory.getLogger(LakeWriter.class);

    private final DuckDb db;
    private final ObjectStore raw;
    private final ObjectStore lake;
    private final Path workDir;

    /** {@code workDir}: local scratch space; DuckDB writes each Parquet file there before upload. */
    public LakeWriter(DuckDb db, ObjectStore raw, ObjectStore lake, Path workDir) {
        this.db = db;
        this.raw = raw;
        this.lake = lake;
        this.workDir = workDir;
    }

    /** Per file: rows in the Parquet file and whether it was written in this run. */
    public record FileResult(SourceFile file, long rows, boolean written) {}

    public List<FileResult> run(LocalDate captureDate) throws IOException, SQLException {
        String manifestKey = RawLayout.manifestKey(captureDate);
        if (!raw.exists(manifestKey)) {
            throw new IllegalStateException("no raw capture for " + captureDate + ": run download first");
        }
        Manifest manifest = Manifest.fromJson(raw.read(manifestKey));
        List<FileResult> results = new ArrayList<>();
        for (SourceFile file : SourceFile.values()) {
            ManifestEntry entry = manifest.entry(file).orElseThrow(() -> new IllegalStateException(
                    "capture " + captureDate + " has no " + file.csvName() + ": download is incomplete"));
            String target = LakeLayout.parquetKey(file, captureDate);
            boolean current = lake.exists(target) && sourceSha256(target).equals(Optional.of(entry.sha256()));
            if (!current) {
                write(file, captureDate, entry, target);
            }
            long rows = rowCount(target);
            LOG.info("{} {}: {} rows", current ? "unchanged" : "wrote", target, rows);
            results.add(new FileResult(file, rows, !current));
        }
        return results;
    }

    private void write(SourceFile file, LocalDate captureDate, ManifestEntry entry, String target)
            throws IOException, SQLException {
        Files.createDirectories(workDir);
        Path tmp = workDir.resolve(file.baseName() + "-" + UUID.randomUUID() + ".parquet");
        try {
            db.execute("COPY (" + readCsv(raw.location(RawLayout.csvKey(captureDate, file))) + ") TO "
                    + DuckDb.literal(tmp.toString())
                    + " (FORMAT parquet, COMPRESSION zstd, KV_METADATA {"
                    + "source_file: " + DuckDb.literal(entry.file())
                    + ", source_sha256: " + DuckDb.literal(entry.sha256())
                    + ", source_size: " + DuckDb.literal(Long.toString(entry.size()))
                    + ", capture_date: " + DuckDb.literal(captureDate.toString()) + "})");
            lake.putAtomically(target, tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * The UR CSV dialect (source assessment section 2): {@code ;}, header, {@code "} quotes with
     * embedded newlines, UTF-8, empty field = NULL. Every column as VARCHAR. No Hive columns from
     * the {@code date=} path.
     */
    static String readCsv(String location) {
        return "SELECT * FROM read_csv(" + DuckDb.literal(location)
                + ", delim = ';', quote = '\"', escape = '\"', header = true, all_varchar = true"
                + ", hive_partitioning = false, encoding = 'utf-8', null_padding = false)";
    }

    /** The {@code source_sha256} value in the Parquet key-value metadata. */
    Optional<String> sourceSha256(String key) throws SQLException {
        return db.queryString("SELECT decode(value) FROM parquet_kv_metadata(" + DuckDb.literal(lake.location(key))
                + ") WHERE decode(key) = 'source_sha256'");
    }

    private long rowCount(String key) throws SQLException {
        return Long.parseLong(db.queryString("SELECT sum(num_rows) FROM parquet_file_metadata("
                + DuckDb.literal(lake.location(key)) + ")").orElse("0"));
    }
}
