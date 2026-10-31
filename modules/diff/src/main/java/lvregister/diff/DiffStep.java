package lvregister.diff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lvregister.domain.ObservedBetween;
import lvregister.domain.Snapshot;
import lvregister.lake.CaptureCounts;
import lvregister.lake.SnapshotLoader;
import lvregister.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code diff} step: compares a capture with the previous capture in the lake and writes
 * the events to {@code events/date=D/events.jsonl}, sorted by id, and the normalize counts of
 * the capture to {@code reports/date=D/normalize.json}. The first capture is the
 * baseline: its events file is empty. Re-running gives byte-identical output.
 */
public final class DiffStep {

    private static final Logger LOG = LoggerFactory.getLogger(DiffStep.class);

    private final ObjectStore lake;
    private final SnapshotLoader loader;

    public DiffStep(ObjectStore lake, SnapshotLoader loader) {
        this.lake = lake;
        this.loader = loader;
    }

    /** What the step did: the capture compared with (empty for the baseline) and events per type. */
    public record Result(Optional<LocalDate> previous, Map<String, Long> eventsByType) {
        public long total() {
            return eventsByType.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    public static String eventsKey(LocalDate date) {
        return "events/date=" + date + "/events.jsonl";
    }

    public Result run(LocalDate date) throws IOException, SQLException {
        List<LocalDate> captures = LakeCaptures.dates(lake);
        if (!captures.contains(date)) {
            throw new IllegalStateException("capture " + date + " is not in the lake: run lake first");
        }
        SnapshotLoader.Loaded current = loader.load(date);
        lake.putAtomically(CaptureCounts.key(date), CaptureCounts.of(current).toJson());
        Optional<LocalDate> previous = LakeCaptures.previous(captures, date);
        if (previous.isEmpty()) {
            lake.putAtomically(eventsKey(date), new byte[0]);
            LOG.info("capture {} is the baseline: no events", date);
            return new Result(Optional.empty(), Map.of());
        }
        Snapshot before = loader.load(previous.get()).snapshot();
        List<StoredEvent> events = EventIds.store(SnapshotDiff.diff(before, current.snapshot()));
        lake.putAtomically(eventsKey(date), jsonLines(events));
        Map<String, Long> byType = events.stream().collect(Collectors.groupingBy(
                e -> EventJson.type(e.event()), TreeMap::new, Collectors.counting()));
        LOG.info("diff {} -> {} ({} days): {} events {}", previous.get(), date,
                new ObservedBetween(previous.get(), date).gapDays(), events.size(), byType);
        return new Result(previous, byType);
    }

    static byte[] jsonLines(List<StoredEvent> events) {
        StringBuilder out = new StringBuilder();
        for (StoredEvent e : events) {
            out.append(EventJson.line(e)).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

}
