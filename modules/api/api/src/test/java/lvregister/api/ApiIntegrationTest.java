package lvregister.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import lvregister.serving.EventView;
import lvregister.serving.RunView;
import lvregister.serving.postgres.PostgresServing;
import lvregister.serving.postgres.PostgresSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The whole application against PostgreSQL in a Testcontainer: the index step's writer fills
 * the store, the API reads it through the port (search, company, network, runs, health, OpenAPI).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer("postgres:18.6-alpine3.24");
        postgres.start();
        FakeQueries data = new FakeQueries();
        try (PostgresServing writer = PostgresServing.connect(
                new PostgresSettings(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()), 2)) {
            writer.prepare();
            writer.upsertCompanies(data.companies.values().stream());
            writer.upsertEvents(List.of(new EventView("e".repeat(64), "NAME_CHANGED", FakeQueries.ALFA, FakeQueries.D1,
                    FakeQueries.D2, "{\"new\":\"SIA \\\"Alfa\\\"\",\"old\":\"SIA \\\"A\\\"\"}")));
            writer.upsertRun(new RunView("run-1", FakeQueries.D2, "OK", "{\"status\":\"OK\"}"));
        }
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @DynamicPropertySource
    static void serving(DynamicPropertyRegistry registry) {
        registry.add("lv.serving.jdbc-url", postgres::getJdbcUrl);
        registry.add("lv.serving.user", postgres::getUsername);
        registry.add("lv.serving.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void readsThePostgresServingStore() throws Exception {
        mvc.perform(get("/api/search").param("q", "alfa"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].regNumber").value(FakeQueries.ALFA));
        mvc.perform(get("/api/companies/" + FakeQueries.ALFA)).andExpect(jsonPath("$.roles.length()").value(1));
        mvc.perform(get("/api/companies/" + FakeQueries.ALFA + "/events")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/companies/" + FakeQueries.ALFA + "/network"))
                .andExpect(jsonPath("$.nodes[?(@.id == 'company:" + FakeQueries.BETA + "')].depth").value(2));
        mvc.perform(get("/api/runs/run-1")).andExpect(jsonPath("$.report.status").value("OK"));
        mvc.perform(get("/api/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.companies.length()").value(0));
    }

    @Test
    void healthIncludesTheServingStore() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.servingStore.status").value("UP"))
                .andExpect(jsonPath("$.components.servingStore.details.latestCapture").value("2026-11-09"));
    }

    @Test
    void openApiDescribesEveryEndpoint() throws Exception {
        mvc.perform(get("/api/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/search']").exists())
                .andExpect(jsonPath("$.paths['/api/companies/{reg}']").exists())
                .andExpect(jsonPath("$.paths['/api/companies/{reg}/events']").exists())
                .andExpect(jsonPath("$.paths['/api/companies/{reg}/network']").exists())
                .andExpect(jsonPath("$.paths['/api/events']").exists())
                .andExpect(jsonPath("$.paths['/api/stats']").exists())
                .andExpect(jsonPath("$.paths['/api/runs']").exists())
                .andExpect(jsonPath("$.paths['/api/runs/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/features/mass-addresses']").exists());
    }

    /**
     * The frontend's types are generated from {@code web/openapi.json}. This test keeps that file
     * equal to the spec the API serves; {@code LV_WRITE_OPENAPI=1} rewrites it.
     */
    @Test
    void theCommittedOpenApiSpecIsCurrent() throws Exception {
        String served = mvc.perform(get("/api/openapi.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        java.util.Map<?, ?> spec = json.readValue(served, java.util.LinkedHashMap.class);
        spec.remove("servers");
        String normalized = json.writeValueAsString(spec) + "\n";
        java.nio.file.Path file = java.nio.file.Path.of("../../../web/openapi.json");
        if ("1".equals(System.getenv("LV_WRITE_OPENAPI"))) {
            java.nio.file.Files.writeString(file, normalized);
        }
        org.assertj.core.api.Assertions.assertThat(file).as("web/openapi.json is stale: run make openapi").exists();
        org.assertj.core.api.Assertions.assertThat(java.nio.file.Files.readString(file)).isEqualTo(normalized);
    }

    @Autowired
    org.springframework.core.env.Environment environment;

    /**
     * Virtual threads deadlocked the API on the 1-CPU VM (one carrier thread, pinned by class
     * loading). They must stay off unless that is solved (application.yml).
     */
    @Test
    void requestsRunOnPlatformThreads() throws Exception {
        org.assertj.core.api.Assertions.assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class)).isFalse();
        org.assertj.core.api.Assertions.assertThat(environment.getProperty("spring.web.resources.cache.cachecontrol.no-cache", Boolean.class)).isTrue();
    }
}
