package lvregister.api;

import lvregister.serving.ServingQueries;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Actuator health of the serving store: one small query through the port. */
@Component("servingStore")
class ServingHealth implements HealthIndicator {

    private final ServingQueries queries;

    ServingHealth(ServingQueries queries) {
        this.queries = queries;
    }

    @Override
    public Health health() {
        try {
            var runs = queries.runs(0, 1);
            return Health.up().withDetail("runs", runs.total())
                    .withDetail("latestCapture", runs.items().isEmpty() ? "none" : runs.items().getFirst().captureDate().toString())
                    .build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
