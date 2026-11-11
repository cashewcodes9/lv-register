package lvregister.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ManifestTest {

    private static final String SHA = "a".repeat(64);

    private static ManifestEntry entry(String file, Optional<String> lastModified) {
        return new ManifestEntry(file, URI.create("https://example.org/" + file), SHA, 42,
                Instant.parse("2026-11-02T05:00:01Z"), lastModified);
    }

    @Test
    void jsonRoundTripKeepsEveryField() {
        Manifest manifest = new Manifest(LocalDate.of(2026, 11, 2),
                List.of(entry("register.csv", Optional.of("2026-11-01T21:25:07")), entry("officers.csv", Optional.empty())));

        Manifest back = Manifest.fromJson(manifest.toJson());

        assertThat(back).isEqualTo(manifest);
        assertThat(new String(manifest.toJson(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"captureDate\" : \"2026-11-02\"")
                .contains("\"fetchedAt\" : \"2026-11-02T05:00:01Z\"");
    }

    @Test
    void entriesAreSortedAndUnique() {
        Manifest manifest = Manifest.empty(LocalDate.of(2026, 11, 2))
                .with(entry("register.csv", Optional.empty()))
                .with(entry("officers.csv", Optional.empty()))
                .with(entry("register.csv", Optional.of("x")));

        assertThat(manifest.files()).extracting(ManifestEntry::file).containsExactly("officers.csv", "register.csv");
        assertThat(manifest.entry(SourceFile.REGISTER).flatMap(ManifestEntry::sourceLastModified)).contains("x");
        assertThatIllegalArgumentException().isThrownBy(() -> new Manifest(LocalDate.of(2026, 11, 2),
                List.of(entry("a.csv", Optional.empty()), entry("a.csv", Optional.empty()))));
    }

    @Test
    void shaMustBeHex() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManifestEntry("a.csv", URI.create("https://x"),
                "ABC", 1, Instant.EPOCH, Optional.empty()));
    }
}
