package lvregister.quality;

import java.util.Map;

/**
 * Warning levels of the quality report. Starting values, to be tuned on real captures.
 *
 * @param dropShare        drop share of a file above which it warns (rows dropped / rows read)
 * @param rowChangeShare   change of rows read vs the previous capture above which it warns
 * @param eventSpikeShare  events of one type per company above which it warns (a spike usually
 *                         means a parsing problem)
 * @param eventSpikeMin    an event type never warns below this count
 * @param storageWarnBytes total bucket size above which it warns
 */
public record Thresholds(double dropShare, double rowChangeShare, double eventSpikeShare, long eventSpikeMin,
        long storageWarnBytes) {

    /** {@code QUALITY_DROP_SHARE}, {@code QUALITY_ROW_CHANGE_SHARE}, {@code QUALITY_EVENT_SPIKE_SHARE},
     *  {@code QUALITY_EVENT_SPIKE_MIN}, {@code STORAGE_WARN_GB}. */
    public static Thresholds fromEnvironment(Map<String, String> env) {
        return new Thresholds(
                Double.parseDouble(env.getOrDefault("QUALITY_DROP_SHARE", "0.01")),
                Double.parseDouble(env.getOrDefault("QUALITY_ROW_CHANGE_SHARE", "0.05")),
                Double.parseDouble(env.getOrDefault("QUALITY_EVENT_SPIKE_SHARE", "0.01")),
                Long.parseLong(env.getOrDefault("QUALITY_EVENT_SPIKE_MIN", "500")),
                (long) (Double.parseDouble(env.getOrDefault("STORAGE_WARN_GB", "10")) * 1024 * 1024 * 1024));
    }

    public static Thresholds defaults() {
        return fromEnvironment(Map.of());
    }
}
