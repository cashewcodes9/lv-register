package lvregister.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** A burst of 3: the fourth request from one IP gets 429; another IP still gets through. */
@WebMvcTest(controllers = FeedController.class, properties = {
        "lv.rate-limit.per-minute=1", "lv.rate-limit.burst=3", "lv.cors.allowed-origins=http://localhost:5173"})
@Import({RateLimitTest.Config.class, CachedQueries.class, RateLimitFilter.class, ApiExceptionHandler.class})
class RateLimitTest {

    @TestConfiguration
    static class Config {
        @Bean
        FakeQueries servingQueries() {
            return new FakeQueries();
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void aBurstIsAllowedThenTooManyRequests() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/runs").with(r -> { r.setRemoteAddr("10.0.0.1"); return r; })).andExpect(status().isOk());
        }
        mvc.perform(get("/api/runs").with(r -> { r.setRemoteAddr("10.0.0.1"); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429));
        mvc.perform(get("/api/runs").with(r -> { r.setRemoteAddr("10.0.0.2"); return r; })).andExpect(status().isOk());
        mvc.perform(get("/api/openapi.json").with(r -> { r.setRemoteAddr("10.0.0.1"); return r; }))
                .andExpect(status().isNotFound());
    }
}
