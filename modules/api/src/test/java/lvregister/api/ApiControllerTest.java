package lvregister.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lvregister.serving.NetworkBuilder;
import lvregister.serving.ServingQueries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {CompanyController.class, FeedController.class}, properties = {
        "lv.rate-limit.per-minute=1000", "lv.rate-limit.burst=1000", "lv.cors.allowed-origins=http://localhost:5173"})
@Import({ApiControllerTest.Config.class, CachedQueries.class, RateLimitFilter.class, WebConfig.class, ApiExceptionHandler.class})
@ImportAutoConfiguration(CacheAutoConfiguration.class)
class ApiControllerTest {

    @TestConfiguration
    @EnableCaching
    static class Config {
        @Bean
        FakeQueries servingQueries() {
            return new FakeQueries();
        }

        @Bean
        NetworkBuilder networkBuilder(ServingQueries queries) {
            return new NetworkBuilder(queries, 30, 300);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeQueries queries;

    @Test
    void searchFindsCompanies() throws Exception {
        mvc.perform(get("/api/search").param("q", "alfa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].regNumber").value(FakeQueries.ALFA))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void invalidParametersAreProblemDetails() throws Exception {
        mvc.perform(get("/api/search").param("q", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/search")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/search").param("q", "alfa").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/companies/123")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/events").param("date", "2026-13-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events").param("type", "name changed")).andExpect(status().isBadRequest());
    }

    @Test
    void companyPageWithNullsInsteadOfOptionals() throws Exception {
        mvc.perform(get("/api/companies/" + FakeQueries.ALFA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("SIA \"Alfa\""))
                .andExpect(jsonPath("$.address.postalCode").value("LV-1050"))
                .andExpect(jsonPath("$.address.atvk").value(nullValue()))
                .andExpect(jsonPath("$.terminated").value(nullValue()))
                .andExpect(jsonPath("$.capital.amount").value("2800"))
                .andExpect(jsonPath("$.roles[0].position").value("BOARD_MEMBER"))
                .andExpect(jsonPath("$.roles[0].holder.personKey").value("ab".repeat(32)));
    }

    @Test
    void unknownResourcesAre404ProblemDetails() throws Exception {
        for (String path : new String[] {"/api/companies/40003999999", "/api/companies/40003999999/events",
                "/api/companies/40003999999/network", "/api/runs/run-9"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound())
                    .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.detail").exists());
        }
    }

    @Test
    void eventsEmbedTheirPayloadAsJson() throws Exception {
        mvc.perform(get("/api/companies/" + FakeQueries.ALFA + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].payload.new").value("SIA \"Alfa\""))
                .andExpect(jsonPath("$.items[0].capture").value("2026-11-09"));
        mvc.perform(get("/api/events").param("date", "2026-11-09").param("type", "NAME_CHANGED"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/events").param("type", "TERMINATED")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void networkLinksCompaniesThroughTheirPeople() throws Exception {
        mvc.perform(get("/api/companies/" + FakeQueries.ALFA + "/network"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root").value("company:" + FakeQueries.ALFA))
                .andExpect(jsonPath("$.nodes.length()").value(3))
                .andExpect(jsonPath("$.nodes[2].id").value("company:" + FakeQueries.BETA))
                .andExpect(jsonPath("$.nodes[2].depth").value(2))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void statsAreCached() throws Exception {
        mvc.perform(get("/api/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].dimensions.legal_family").value("LLC"))
                .andExpect(jsonPath("$.eventsPerCapture[0].value").value(1));
        int calls = queries.statsCalls.get();
        mvc.perform(get("/api/stats")).andExpect(status().isOk());
        assertThat(queries.statsCalls.get()).isEqualTo(calls);
    }

    @Test
    void runsAndTheirReports() throws Exception {
        mvc.perform(get("/api/runs")).andExpect(jsonPath("$.items[0].runId").value("run-1"))
                .andExpect(jsonPath("$.items[0].report").doesNotExist());
        mvc.perform(get("/api/runs/run-1")).andExpect(jsonPath("$.report.status").value("OK"));
    }

    @Test
    void massAddresses() throws Exception {
        mvc.perform(get("/api/features/mass-addresses"))
                .andExpect(jsonPath("$.items[0].vzdId").value("100"))
                .andExpect(jsonPath("$.items[0].data.active_companies").value(185));
    }

    @Test
    void corsOnlyForTheViteDevServer() throws Exception {
        mvc.perform(options("/api/stats").header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/stats").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
