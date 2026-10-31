package lvregister.serving.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import lvregister.serving.CompanyView;
import lvregister.serving.EventView;
import lvregister.serving.Page;
import lvregister.serving.RunView;
import lvregister.serving.SearchHit;
import lvregister.serving.ServingQueries;
import lvregister.serving.ServingWriter;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.json.JsonMapper;

/**
 * The serving store on PostgreSQL (docs/architecture.md, "Serving"): companies as JSON documents
 * with a trigram search column, events and runs with their JSON as written by the pipeline.
 * Plain JDBC; the schema is a Flyway migration run by {@link #prepare()}.
 */
public final class PostgresServing implements ServingWriter, ServingQueries, AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int BATCH = 1000;

    private final DataSource dataSource;
    private final Runnable closer;

    public PostgresServing(DataSource dataSource) {
        this(dataSource, () -> { });
    }

    private PostgresServing(DataSource dataSource, Runnable closer) {
        this.dataSource = dataSource;
        this.closer = closer;
    }

    /** A small pool: the pipeline writes from one thread, the API reads with a few. */
    public static PostgresServing connect(PostgresSettings settings, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.user());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(maxPoolSize);
        config.setPoolName("serving");
        HikariDataSource ds = new HikariDataSource(config);
        return new PostgresServing(ds, ds::close);
    }

    // ---- writer ----

    @Override
    public void prepare() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/serving")
                .table("serving_schema_history").load().migrate();
    }

    @Override
    public Upserted upsertCompanies(Stream<CompanyView> companies) {
        String sql = """
                INSERT INTO companies (reg_number, name, legal_form, legal_family, status, as_of, doc, doc_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (reg_number) DO UPDATE SET
                    name = EXCLUDED.name, legal_form = EXCLUDED.legal_form, legal_family = EXCLUDED.legal_family,
                    status = EXCLUDED.status, as_of = EXCLUDED.as_of, doc = EXCLUDED.doc, doc_hash = EXCLUDED.doc_hash,
                    updated_at = now()
                WHERE companies.as_of <= EXCLUDED.as_of AND companies.doc_hash <> EXCLUDED.doc_hash
                """;
        long[] counts = new long[2];
        withConnection(conn -> {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int pending = 0;
                Iterator<CompanyView> it = companies.iterator();
                while (it.hasNext()) {
                    CompanyView c = it.next();
                    ps.setString(1, c.regNumber());
                    ps.setString(2, c.name());
                    ps.setString(3, c.legalFormCode());
                    ps.setString(4, c.legalFamily());
                    ps.setString(5, c.status());
                    ps.setDate(6, Date.valueOf(c.asOf()));
                    ps.setString(7, JSON.writeValueAsString(c));
                    ps.setString(8, contentHash(c));
                    ps.addBatch();
                    if (++pending == BATCH) {
                        count(ps.executeBatch(), counts);
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    count(ps.executeBatch(), counts);
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
            return null;
        });
        return new Upserted(counts[0], counts[1]);
    }

    /** sha256 of the document without {@code asOf}: an unchanged company is not written again. */
    static String contentHash(CompanyView c) {
        CompanyView withoutDate = new CompanyView(c.regNumber(), c.name(), c.legalFormCode(), c.legalFamily(), c.status(),
                c.address(), c.registered(), c.terminated(), c.capital(), c.roles(), LocalDate.EPOCH);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(withoutDate).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Upserted upsertEvents(List<EventView> events) {
        String sql = """
                INSERT INTO events (id, type, reg_number, previous, current, payload) VALUES (?, ?, ?, ?, ?, ?::json)
                ON CONFLICT (id) DO NOTHING
                """;
        long[] counts = new long[2];
        withConnection(conn -> {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int pending = 0;
                for (EventView e : events) {
                    ps.setString(1, e.id());
                    ps.setString(2, e.type());
                    ps.setString(3, e.regNumber());
                    ps.setDate(4, Date.valueOf(e.previous()));
                    ps.setDate(5, Date.valueOf(e.current()));
                    ps.setString(6, e.payloadJson());
                    ps.addBatch();
                    if (++pending == BATCH) {
                        count(ps.executeBatch(), counts);
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    count(ps.executeBatch(), counts);
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
            return null;
        });
        return new Upserted(counts[0], counts[1]);
    }

    @Override
    public void upsertRun(RunView run) {
        withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO runs (run_id, capture_date, status, report) VALUES (?, ?, ?, ?::json)
                    ON CONFLICT (run_id) DO UPDATE SET capture_date = EXCLUDED.capture_date, status = EXCLUDED.status,
                        report = EXCLUDED.report, updated_at = now()
                    """)) {
                ps.setString(1, run.runId());
                ps.setDate(2, Date.valueOf(run.captureDate()));
                ps.setString(3, run.status());
                ps.setString(4, run.reportJson());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static void count(int[] results, long[] counts) {
        for (int r : results) {
            if (r > 0) {
                counts[0]++;
            } else {
                counts[1]++;
            }
        }
    }

    // ---- queries ----

    /** Normalized query text and its LIKE pattern, computed in SQL so search and index agree. */
    private static final String QUERY = """
            WITH q AS (SELECT lower(lv_unaccent(?)) AS t, ? AS raw),
                 p AS (SELECT t, raw, '%' || replace(replace(replace(t, '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%' AS pattern FROM q)
            """;
    private static final String MATCH =
            "(c.reg_number = p.raw OR c.name_search LIKE p.pattern ESCAPE '\\' OR c.name_search % p.t)";

    @Override
    public Page<SearchHit> search(String query, int page, int size) {
        Page.check(page, size);
        String text = query == null ? "" : query.strip();
        if (text.isEmpty()) {
            return new Page<>(List.of(), page, size, 0);
        }
        return withConnection(conn -> {
            long total;
            try (PreparedStatement ps = conn.prepareStatement(QUERY + "SELECT count(*) FROM companies c, p WHERE " + MATCH)) {
                ps.setString(1, text);
                ps.setString(2, text);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<SearchHit> hits = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(QUERY
                    + "SELECT c.reg_number, c.name, c.legal_form, c.status,"
                    + " CASE WHEN c.reg_number = p.raw THEN 2.0 ELSE similarity(c.name_search, p.t) END AS score"
                    + " FROM companies c, p WHERE " + MATCH
                    + " ORDER BY score DESC, c.name, c.reg_number LIMIT ? OFFSET ?")) {
                ps.setString(1, text);
                ps.setString(2, text);
                ps.setInt(3, size);
                ps.setLong(4, (long) page * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        hits.add(new SearchHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5)));
                    }
                }
            }
            return new Page<>(hits, page, size, total);
        });
    }

    @Override
    public Optional<CompanyView> company(String regNumber) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT doc::text FROM companies WHERE reg_number = ?")) {
                ps.setString(1, regNumber);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(JSON.readValue(rs.getString(1), CompanyView.class)) : Optional.empty();
                }
            }
        });
    }

    private static final String EVENT_COLUMNS = "id, type, reg_number, previous, current, payload::text";

    @Override
    public Page<EventView> eventsOf(String regNumber, int page, int size) {
        Page.check(page, size);
        return eventPage("WHERE reg_number = ?", List.of(regNumber), page, size);
    }

    @Override
    public Page<EventView> events(Optional<LocalDate> captureDate, Optional<String> type, int page, int size) {
        Page.check(page, size);
        List<Object> args = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        captureDate.ifPresent(d -> {
            conditions.add("current = ?");
            args.add(Date.valueOf(d));
        });
        type.ifPresent(t -> {
            conditions.add("type = ?");
            args.add(t);
        });
        return eventPage(conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions), args, page, size);
    }

    private Page<EventView> eventPage(String where, List<Object> args, int page, int size) {
        return withConnection(conn -> {
            long total;
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM events " + where)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<EventView> items = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + EVENT_COLUMNS + " FROM events " + where
                    + " ORDER BY current DESC, type, id LIMIT ? OFFSET ?")) {
                bind(ps, args);
                ps.setInt(args.size() + 1, size);
                ps.setLong(args.size() + 2, (long) page * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(new EventView(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getDate(4).toLocalDate(), rs.getDate(5).toLocalDate(), rs.getString(6)));
                    }
                }
            }
            return new Page<>(items, page, size, total);
        });
    }

    @Override
    public Page<RunView> runs(int page, int size) {
        Page.check(page, size);
        return withConnection(conn -> {
            long total;
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM runs"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getLong(1);
            }
            List<RunView> items = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT run_id, capture_date, status, report::text FROM runs "
                    + "ORDER BY capture_date DESC, updated_at DESC, run_id LIMIT ? OFFSET ?")) {
                ps.setInt(1, size);
                ps.setLong(2, (long) page * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(run(rs));
                    }
                }
            }
            return new Page<>(items, page, size, total);
        });
    }

    @Override
    public Optional<RunView> run(String runId) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT run_id, capture_date, status, report::text FROM runs WHERE run_id = ?")) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(run(rs)) : Optional.empty();
                }
            }
        });
    }

    private static RunView run(ResultSet rs) throws SQLException {
        return new RunView(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getString(3), rs.getString(4));
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
        }
    }

    // ---- plumbing ----

    private interface Work<T> {
        T run(Connection conn) throws SQLException;
    }

    private <T> T withConnection(Work<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            return work.run(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("serving database: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        closer.run();
    }
}
