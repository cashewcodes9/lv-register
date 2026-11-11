package lvregister.serving;

import java.util.Map;
import java.util.TreeMap;

/**
 * One row of an aggregate from the dbt marts, e.g. stat {@code companies_by_form_status_region}
 * with dimensions {legal_family, legal_form_code, status, region_code} and a count.
 */
public record StatView(String stat, Map<String, String> dimensions, long value) {

    public StatView {
        dimensions = new TreeMap<>(dimensions);
    }

    /** Stable key of the row within its stat: the dimension values in name order. */
    public String key() {
        return String.join("|", dimensions.values());
    }
}
