package lvregister.lake;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import lvregister.storage.S3Settings;

/**
 * One in-memory DuckDB connection with resource limits and, when given S3 settings, the
 * {@code httpfs} extension and a secret for the S3-compatible store (SeaweedFS).
 */
public final class DuckDb implements AutoCloseable {

    private final Connection connection;

    private DuckDb(Connection connection) {
        this.connection = connection;
    }

    public static DuckDb open(DuckDbSettings settings, Optional<S3Settings> s3) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:duckdb:");
        DuckDb db = new DuckDb(connection);
        try {
            db.execute("SET memory_limit = " + literal(settings.memoryLimit()));
            db.execute("SET threads = " + settings.threads());
            db.execute("SET temp_directory = " + literal(settings.tempDirectory().toString()));
            db.execute("SET preserve_insertion_order = true");
            if (settings.extensionDirectory().isPresent()) {
                db.execute("SET extension_directory = " + literal(settings.extensionDirectory().get().toString()));
            }
            if (s3.isPresent()) {
                S3Settings s = s3.get();
                db.execute("INSTALL httpfs");
                db.execute("LOAD httpfs");
                db.execute("CREATE SECRET lake_s3 (TYPE s3"
                        + ", KEY_ID " + literal(s.accessKey())
                        + ", SECRET " + literal(s.secretKey())
                        + ", REGION " + literal(s.region())
                        + ", ENDPOINT " + literal(s.endpointHostPort())
                        + ", URL_STYLE 'path'"
                        + ", USE_SSL " + s.useSsl() + ")");
            }
            return db;
        } catch (SQLException | RuntimeException e) {
            db.close();
            throw e;
        }
    }

    public void execute(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    /** First column of the first row as text, or empty when there is no row or it is NULL. */
    public Optional<String> queryString(String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        }
    }

    Connection connection() {
        return connection;
    }

    /** A SQL string literal. Values are our own keys and settings, quoted defensively. */
    public static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** A quoted SQL identifier. */
    public static String identifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
