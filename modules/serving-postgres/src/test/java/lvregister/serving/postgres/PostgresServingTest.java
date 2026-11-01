package lvregister.serving.postgres;

import java.sql.Connection;
import java.sql.Statement;
import lvregister.serving.ServingContract;
import lvregister.serving.ServingQueries;
import lvregister.serving.ServingWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The serving contract against PostgreSQL 18 (same image as the compose `postgres` service). */
class PostgresServingTest extends ServingContract {

    private static PostgreSQLContainer postgres;
    private static PGSimpleDataSource dataSource;
    private static PostgresServing serving;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer("postgres:18.6-alpine3.24");
        postgres.start();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        serving = new PostgresServing(dataSource);
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @Override
    protected ServingWriter writer() {
        return serving;
    }

    @Override
    protected ServingQueries queries() {
        return serving;
    }

    @Override
    protected void reset() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE companies, party_roles, events, runs, stats, features");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
