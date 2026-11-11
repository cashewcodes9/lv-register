package lvregister.api;

import java.util.List;
import lvregister.serving.Page;
import lvregister.serving.SearchHit;
import lvregister.serving.ServingQueries;
import lvregister.serving.StatView;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Caffeine-cached reads (PLAN.md section 13): stats and search change only when a capture is
 * indexed, once a week, so ten minutes of staleness is fine (spring.cache.caffeine.spec).
 */
@Service
class CachedQueries {

    private final ServingQueries queries;

    CachedQueries(ServingQueries queries) {
        this.queries = queries;
    }

    @Cacheable("stats")
    public List<StatView> stats(String stat) {
        return queries.stats(stat);
    }

    @Cacheable("search")
    public Page<SearchHit> search(String query, int page, int size) {
        return queries.search(query, page, size);
    }
}
