package lvregister.normalize;

import java.util.HashMap;
import java.util.Map;

/** Builds synthetic source rows: rows("col", "value", ...). Null values mean NULL. */
final class Rows {

    private Rows() {}

    static Row row(String... columnsAndValues) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            if (columnsAndValues[i + 1] != null) {
                values.put(columnsAndValues[i], columnsAndValues[i + 1]);
            }
        }
        return new Row(values);
    }
}
