package lvregister.lake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lvregister.ingest.SourceFile;
import lvregister.storage.ObjectStore;

/**
 * Reads the rows of one file of one capture from the lake. Each row is a map from column name
 * to text; NULL columns are left out, so a missing key means "no value". The stream holds a
 * DuckDB result set: close it (try-with-resources).
 */
public final class LakeReader {

    private final DuckDb db;
    private final ObjectStore lake;

    public LakeReader(DuckDb db, ObjectStore lake) {
        this.db = db;
        this.lake = lake;
    }

    /** The column names of a file of a capture, in file order. */
    public List<String> columns(SourceFile file, LocalDate captureDate) throws IOException, SQLException {
        String key = LakeLayout.parquetKey(file, captureDate);
        if (!lake.exists(key)) {
            throw new IllegalStateException("not in the lake: " + key);
        }
        return db.queryRows("DESCRIBE SELECT * FROM read_parquet(" + DuckDb.literal(lake.location(key))
                + ", hive_partitioning = false)").stream().map(row -> row.get("column_name")).toList();
    }

    public Stream<Map<String, String>> rows(SourceFile file, LocalDate captureDate, List<String> columns)
            throws IOException, SQLException {
        String key = LakeLayout.parquetKey(file, captureDate);
        if (!lake.exists(key)) {
            throw new IllegalStateException("not in the lake: " + key);
        }
        String select = columns.stream().map(DuckDb::identifier).collect(Collectors.joining(", "));
        Statement st = db.connection().createStatement();
        ResultSet rs;
        try {
            rs = st.executeQuery("SELECT " + select + " FROM read_parquet(" + DuckDb.literal(lake.location(key))
                    + ", hive_partitioning = false)");
        } catch (SQLException e) {
            st.close();
            throw e;
        }
        Spliterator<Map<String, String>> rowsOf = new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE,
                Spliterator.ORDERED | Spliterator.NONNULL) {
            @Override
            public boolean tryAdvance(Consumer<? super Map<String, String>> action) {
                try {
                    if (!rs.next()) {
                        return false;
                    }
                    Map<String, String> row = new HashMap<>();
                    for (int i = 0; i < columns.size(); i++) {
                        String value = rs.getString(i + 1);
                        if (value != null) {
                            row.put(columns.get(i), value);
                        }
                    }
                    action.accept(row);
                    return true;
                } catch (SQLException e) {
                    throw new UncheckedIOException(new IOException("reading " + key + " failed", e));
                }
            }
        };
        return StreamSupport.stream(rowsOf, false).onClose(() -> {
            try {
                rs.close();
                st.close();
            } catch (SQLException e) {
                throw new UncheckedIOException(new IOException("closing " + key + " failed", e));
            }
        });
    }
}
