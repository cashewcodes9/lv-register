package lvregister.api;

import lvregister.serving.NetworkBuilder;
import lvregister.serving.ServingQueries;
import lvregister.serving.postgres.PostgresServing;
import lvregister.serving.postgres.PostgresSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The serving backend behind the port, chosen by {@code SERVING_BACKEND} (PLAN.md section 4).
 * Only this class knows the adapter; everything else sees {@link ServingQueries}. The API never
 * migrates the schema: the index step owns it.
 */
@Configuration(proxyBeanMethods = false)
class ServingConfig {

    @Bean(destroyMethod = "close")
    ServingQueries servingQueries(@Value("${lv.serving.backend}") String backend, @Value("${lv.serving.jdbc-url}") String url,
            @Value("${lv.serving.user}") String user, @Value("${lv.serving.password}") String password,
            @Value("${lv.serving.pool-size}") int poolSize) {
        if (!backend.equals("postgres")) {
            throw new IllegalArgumentException("unknown SERVING_BACKEND '" + backend + "': only postgres is implemented");
        }
        return PostgresServing.connect(new PostgresSettings(url, user, password), poolSize);
    }

    @Bean
    NetworkBuilder networkBuilder(ServingQueries queries, @Value("${lv.network.max-companies}") int maxCompanies,
            @Value("${lv.network.max-nodes}") int maxNodes) {
        return new NetworkBuilder(queries, maxCompanies, maxNodes);
    }
}
