package lvregister.serving.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Stream;
import lvregister.serving.Page;
import lvregister.serving.SearchHit;
import lvregister.serving.ServingContract;
import lvregister.serving.ServingQueries;
import lvregister.serving.ServingWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
            st.execute("TRUNCATE companies, party_roles, company_search, events, runs, stats, features");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A common word (more substring matches than the threshold) is ranked cheaply, without fuzzy matches. */
    @Test
    void broadQueriesRankExactThenPrefixThenShorterNames() {
        writer().upsertCompanies(Stream.of(
                company("40003000001", "SIA \"Lielā Alfa Grupa\"", D1),
                company("40003000002", "Alfa", D1),
                company("40003000003", "Alfa Serviss", D1),
                company("40003000004", "SIA \"Alfa\"", D1),
                company("40003000005", "SIA \"Alga\"", D1)));
        PostgresServing broad = new PostgresServing(dataSource, 2);

        Page<SearchHit> hits = broad.search("alfa", 0, 10);

        assertThat(hits.items()).extracting(SearchHit::regNumber)
                .containsExactly("40003000002", "40003000003", "40003000004", "40003000001");
        assertThat(hits.total()).isEqualTo(4);
        assertThat(queries().search("alfa", 0, 10).items()).extracting(SearchHit::regNumber).contains("40003000002");
    }
}
