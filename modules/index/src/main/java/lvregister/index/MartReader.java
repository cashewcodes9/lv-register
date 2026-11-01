package lvregister.index;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lvregister.lake.DuckDb;
import lvregister.serving.FeatureView;
import lvregister.serving.ServingNames;
import lvregister.serving.StatView;
import lvregister.storage.ObjectStore;

/** Reads the small dbt marts that the serving store needs (stats and features) from {@code lv-marts}. */
public final class MartReader {

    public static final String COMPANIES_STAT = ServingNames.COMPANIES_STAT;
    public static final String EVENTS_STAT = ServingNames.EVENTS_STAT;
    public static final String MASS_ADDRESSES = ServingNames.MASS_ADDRESSES;

    private final DuckDb db;
    private final ObjectStore marts;

    public MartReader(DuckDb db, ObjectStore marts) {
        this.db = db;
        this.marts = marts;
    }

    /** The marts as serving rows, or empty when dbt has not built them yet. */
    public record Marts(Map<String, List<StatView>> stats, Map<String, List<FeatureView>> features) {}

    public Optional<Marts> read() throws IOException, SQLException {
        List<String> files = List.of("agg_companies_by_form_status_region.parquet", "agg_events_per_capture.parquet",
                "feat_mass_registration_addresses.parquet");
        for (String file : files) {
            if (!marts.exists(file)) {
                return Optional.empty();
            }
        }
        List<StatView> companies = stats(COMPANIES_STAT, files.get(0),
                List.of("legal_family", "legal_form_code", "status", "region_code"), "companies");
        List<StatView> events = stats(EVENTS_STAT, files.get(1),
                List.of("capture_date", "previous_capture", "gap_days", "event_type"), "events");
        List<FeatureView> addresses = db.queryRows("SELECT cast(vzd_id AS VARCHAR) AS key, rank, to_json(m)::VARCHAR AS json FROM "
                        + parquet(files.get(2)) + " m ORDER BY rank").stream()
                .map(row -> new FeatureView(MASS_ADDRESSES, row.get("key"), Integer.parseInt(row.get("rank")), row.get("json")))
                .toList();
        return Optional.of(new Marts(new java.util.TreeMap<>(Map.of(COMPANIES_STAT, companies, EVENTS_STAT, events)),
                new java.util.TreeMap<>(Map.of(MASS_ADDRESSES, addresses))));
    }

    /** Rows of {@code dim_companies}, or empty when dbt has not built it yet. */
    public Optional<Long> companies() throws IOException, SQLException {
        String file = "dim_companies.parquet";
        if (!marts.exists(file)) {
            return Optional.empty();
        }
        return db.queryString("SELECT count(*) FROM " + parquet(file)).map(Long::parseLong);
    }

    private List<StatView> stats(String stat, String file, List<String> dimensions, String value) throws SQLException {
        String select = dimensions.stream().map(d -> "coalesce(cast(" + DuckDb.identifier(d) + " AS VARCHAR), '') AS " + DuckDb.identifier(d))
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        return db.queryRows("SELECT " + select + ", " + DuckDb.identifier(value) + " AS value FROM " + parquet(file)).stream()
                .map(row -> {
                    Map<String, String> dims = new java.util.TreeMap<>();
                    dimensions.forEach(d -> dims.put(d, row.get(d)));
                    return new StatView(stat, dims, Long.parseLong(row.get("value")));
                })
                .toList();
    }

    private String parquet(String file) {
        return "read_parquet(" + DuckDb.literal(marts.location(file)) + ")";
    }
}
