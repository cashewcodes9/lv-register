package lvregister.pipes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The fields of Dagster's {@code PipesContextData} that the CLI uses.
 *
 * @param assetKeys    the asset keys of the step, as escaped user strings ({@code a/b})
 * @param partitionKey the partition of the run, if any
 * @param runId        the Dagster run id
 */
public record PipesContext(List<String> assetKeys, Optional<String> partitionKey, String runId) {

    public PipesContext {
        assetKeys = List.copyOf(assetKeys);
    }

    static PipesContext fromJson(JsonNode data) {
        List<String> keys = new ArrayList<>();
        JsonNode assetKeys = data.path("asset_keys");
        if (assetKeys.isArray()) {
            assetKeys.forEach(k -> keys.add(k.asString()));
        }
        JsonNode partition = data.path("partition_key");
        return new PipesContext(keys,
                partition.isString() ? Optional.of(partition.asString()) : Optional.empty(),
                data.path("run_id").asString(""));
    }

    /** The only asset of the step; reports without an explicit key go to it. */
    public String singleAssetKey() {
        if (assetKeys.size() != 1) {
            throw new IllegalStateException("the step targets " + assetKeys.size()
                    + " assets; a report needs an explicit asset key");
        }
        return assetKeys.getFirst();
    }
}
