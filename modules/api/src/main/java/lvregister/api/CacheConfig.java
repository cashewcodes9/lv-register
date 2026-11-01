package lvregister.api;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/** Caffeine caches {@code stats} and {@code search} (spring.cache in application.yml). */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {
}
