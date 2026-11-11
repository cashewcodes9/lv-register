package lvregister.lake;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import lvregister.ingest.Manifest;
import lvregister.ingest.ManifestEntry;
import lvregister.ingest.RawLayout;
import lvregister.ingest.SourceFile;
import lvregister.storage.ObjectStore;

/** Writes a synthetic raw capture (CSV files and manifest), as the download step would. */
final class RawCaptures {

    /** register.csv with the dialect quirks of the real file. Synthetic values only. */
    static final String REGISTER = String.join("\n",
            "regcode;name;type;closed;address;grounds",
            "012345678;\"SIA \"\"Alfa\"\"\";SIA; ;Rīga, Brīvības iela 1;",
            "40003000001;\"AS \"\"Beta\"\"\";AS;L;;\"line one",
            "line two\"",
            "");

    private RawCaptures() {}

    static void write(ObjectStore raw, LocalDate date, Map<SourceFile, String> overrides) throws IOException {
        Manifest manifest = Manifest.empty(date);
        for (SourceFile file : SourceFile.values()) {
            String csv = overrides.getOrDefault(file, file == SourceFile.REGISTER ? REGISTER : "id;value\n1;" + file.baseName() + "\n");
            byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
            raw.put(RawLayout.csvKey(date, file), bytes);
            manifest = manifest.with(new ManifestEntry(file.csvName(), URI.create("https://example.org/" + file.csvName()),
                    sha256(bytes), bytes.length, Instant.parse("2026-11-02T05:00:00Z"), Optional.empty()));
        }
        raw.put(RawLayout.manifestKey(date), manifest.toJson());
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
