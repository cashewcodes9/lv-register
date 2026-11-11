package lvregister.ingest;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;

/** The raw files of one capture, sorted by file name. Stored as {@code manifest.json}. */
public record Manifest(LocalDate captureDate, List<ManifestEntry> files) {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    public Manifest {
        if (captureDate == null || files == null) {
            throw new IllegalArgumentException("captureDate and files must be set");
        }
        files = files.stream().sorted(Comparator.comparing(ManifestEntry::file)).toList();
        if (files.stream().map(ManifestEntry::file).distinct().count() != files.size()) {
            throw new IllegalArgumentException("a file appears twice in the manifest");
        }
    }

    public static Manifest empty(LocalDate captureDate) {
        return new Manifest(captureDate, List.of());
    }

    public Optional<ManifestEntry> entry(SourceFile file) {
        return files.stream().filter(e -> e.file().equals(file.csvName())).findFirst();
    }

    /** A new manifest with the entry added or replaced. */
    public Manifest with(ManifestEntry entry) {
        return new Manifest(captureDate,
                Stream.concat(files.stream().filter(e -> !e.file().equals(entry.file())), Stream.of(entry)).toList());
    }

    public byte[] toJson() {
        return JSON.writeValueAsBytes(this);
    }

    public static Manifest fromJson(byte[] json) {
        return JSON.readValue(json, Manifest.class);
    }
}
