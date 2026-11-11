package lvregister.lake;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The normalize figures of one capture, written by the diff step to
 * {@code reports/date=D/normalize.json} so the quality step does not load the snapshot again.
 *
 * @param files per file base name ({@code register}, ...): rows kept, dropped and flagged per rule
 */
public record CaptureCounts(
        LocalDate captureDate,
        long companies,
        long companiesWithRoles,
        long roles,
        long orphanRoles,
        long duplicateRoles,
        Map<String, SnapshotLoader.FileCounts> files) {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public CaptureCounts {
        files = new TreeMap<>(files);
    }

    public static String key(LocalDate date) {
        return "reports/date=" + date + "/normalize.json";
    }

    public static CaptureCounts of(SnapshotLoader.Loaded loaded) {
        Map<String, SnapshotLoader.FileCounts> files = new TreeMap<>();
        loaded.counts().forEach((file, counts) -> files.put(file.baseName(), counts));
        var snapshot = loaded.snapshot();
        return new CaptureCounts(snapshot.date(), snapshot.companies().size(), snapshot.roles().size(),
                snapshot.roles().values().stream().mapToLong(java.util.Set::size).sum(),
                loaded.orphanRoles(), loaded.duplicateRoles(), files);
    }

    public byte[] toJson() {
        return JSON.writeValueAsBytes(this);
    }

    public static CaptureCounts fromJson(byte[] json) {
        return JSON.readValue(json, CaptureCounts.class);
    }
}
