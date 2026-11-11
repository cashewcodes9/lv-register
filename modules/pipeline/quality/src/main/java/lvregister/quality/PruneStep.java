package lvregister.quality;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lvregister.diff.DiffStep;
import lvregister.diff.LakeCaptures;
import lvregister.ingest.RawLayout;
import lvregister.ingest.SourceFile;
import lvregister.lake.LakeLayout;
import lvregister.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code prune} step: bounded retention (PLAN.md section 6.2). Keeps the CSVs of the latest
 * {@code rawKeep} captures and the lake snapshots of the latest {@code lakeKeep} captures.
 * Manifests, events, counts and reports are kept forever.
 *
 * <p>Safety rules: the latest capture is always kept; raw CSVs are deleted only once the lake
 * holds every file of the capture; a snapshot is deleted only once its own events and the
 * events of the next capture (whose diff reads it) are written.
 */
public final class PruneStep {

    private static final Logger LOG = LoggerFactory.getLogger(PruneStep.class);

    private final ObjectStore raw;
    private final ObjectStore lake;
    private final int rawKeep;
    private final int lakeKeep;

    public PruneStep(ObjectStore raw, ObjectStore lake, int rawKeep, int lakeKeep) {
        if (rawKeep < 1 || lakeKeep < 1) {
            throw new IllegalArgumentException("RAW_KEEP and LAKE_KEEP must be at least 1");
        }
        this.raw = raw;
        this.lake = lake;
        this.rawKeep = rawKeep;
        this.lakeKeep = lakeKeep;
    }

    /** {@code RAW_KEEP} (default 2) and {@code LAKE_KEEP} (default 12). */
    public static PruneStep fromEnvironment(ObjectStore raw, ObjectStore lake, Map<String, String> env) {
        return new PruneStep(raw, lake, Integer.parseInt(env.getOrDefault("RAW_KEEP", "2")),
                Integer.parseInt(env.getOrDefault("LAKE_KEEP", "12")));
    }

    /**
     * What was deleted. {@code kept}: captures that were due but kept by a safety rule.
     */
    public record Result(List<LocalDate> rawPruned, List<LocalDate> lakePruned, Map<LocalDate, String> kept,
            long bytesFreed) {

        public Result {
            rawPruned = List.copyOf(rawPruned);
            lakePruned = List.copyOf(lakePruned);
            kept = new TreeMap<>(kept);
        }
    }

    public Result run() throws IOException {
        List<LocalDate> lakeCaptures = LakeCaptures.dates(lake);
        Map<LocalDate, String> kept = new TreeMap<>();
        long freed = 0;

        List<LocalDate> rawPruned = new ArrayList<>();
        List<LocalDate> rawCaptures = RawLayout.captures(raw);
        for (LocalDate date : due(rawCaptures, rawKeep)) {
            List<String> csvs = raw.list(RawLayout.prefix(date)).stream()
                    .filter(k -> !k.equals(RawLayout.manifestKey(date))).toList();
            if (csvs.isEmpty()) {
                continue;
            }
            if (!lakeComplete(date)) {
                kept.put(date, "raw: the lake does not hold every file of the capture");
                continue;
            }
            for (String key : csvs) {
                freed += raw.size(key).orElse(0L);
                raw.delete(key);
            }
            rawPruned.add(date);
        }

        List<LocalDate> lakePruned = new ArrayList<>();
        for (LocalDate date : due(lakeCaptures, lakeKeep)) {
            LocalDate next = lakeCaptures.get(lakeCaptures.indexOf(date) + 1);
            if (!lake.exists(DiffStep.eventsKey(date)) || !lake.exists(DiffStep.eventsKey(next))) {
                kept.put(date, "lake: events of " + date + " or of the next capture " + next + " are not written");
                continue;
            }
            for (SourceFile file : SourceFile.values()) {
                String key = LakeLayout.parquetKey(file, date);
                freed += lake.size(key).orElse(0L);
                lake.delete(key);
            }
            lakePruned.add(date);
        }
        LOG.info("prune: raw CSVs of {} deleted, lake snapshots of {} deleted, {} kept by safety rules, {} bytes freed",
                rawPruned, lakePruned, kept.size(), freed);
        return new Result(rawPruned, lakePruned, kept, freed);
    }

    /** The captures beyond the latest {@code keep}, oldest first. */
    static List<LocalDate> due(List<LocalDate> captures, int keep) {
        List<LocalDate> sorted = captures.stream().sorted().toList();
        return sorted.subList(0, Math.max(0, sorted.size() - keep));
    }

    private boolean lakeComplete(LocalDate date) throws IOException {
        for (SourceFile file : SourceFile.values()) {
            if (!lake.exists(LakeLayout.parquetKey(file, date))) {
                return false;
            }
        }
        return true;
    }
}
