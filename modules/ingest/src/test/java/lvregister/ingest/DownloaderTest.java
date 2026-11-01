package lvregister.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import lvregister.storage.LocalDirectoryObjectStore;
import lvregister.storage.ObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloaderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    @TempDir
    Path tmp;

    private FakeUr ur;
    private ObjectStore raw;
    private Downloader downloader;

    @BeforeEach
    void setUp() throws IOException {
        ur = new FakeUr();
        raw = new LocalDirectoryObjectStore(tmp.resolve("raw"));
        Http http = new Http(HttpClient.newHttpClient(), 3, Duration.ofMillis(1));
        downloader = new Downloader(http, ur.catalogUrl(), raw, tmp.resolve("work"),
                Clock.fixed(Instant.parse("2026-11-02T05:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        ur.close();
    }

    @Test
    void firstRunStoresEveryFileWithItsSha256() throws Exception {
        Downloader.Result result = downloader.run(DAY);

        assertThat(result.downloaded()).isEqualTo(SourceFile.values().length);
        assertThat(result.skipped()).isZero();
        Manifest manifest = Manifest.fromJson(raw.read("date=2026-11-02/manifest.json"));
        assertThat(manifest.files()).hasSize(SourceFile.values().length);
        for (SourceFile file : SourceFile.values()) {
            byte[] stored = raw.read("date=2026-11-02/" + file.csvName());
            assertThat(new String(stored, StandardCharsets.UTF_8)).isEqualTo(ur.files.get(file.csvName()));
            ManifestEntry entry = manifest.entry(file).orElseThrow();
            assertThat(entry.sha256()).isEqualTo(sha256(stored));
            assertThat(entry.size()).isEqualTo(stored.length);
            assertThat(entry.sourceLastModified()).contains("2026-11-01T21:25:07");
        }
        assertThat(raw.list("")).allSatisfy(key -> assertThat(key).startsWith("date=2026-11-02/"));
        assertThat(tmp.resolve("work").toFile().list()).isEmpty();
    }

    @Test
    void secondRunDownloadsNothing() throws Exception {
        downloader.run(DAY);
        byte[] manifestBefore = raw.read("date=2026-11-02/manifest.json");
        ur.files.replaceAll((name, content) -> content + "2;changed\n");

        Downloader.Result again = downloader.run(DAY);

        assertThat(again.downloaded()).isZero();
        assertThat(again.skipped()).isEqualTo(SourceFile.values().length);
        assertThat(ur.fileRequests()).isEqualTo(SourceFile.values().length);
        assertThat(raw.read("date=2026-11-02/manifest.json")).isEqualTo(manifestBefore);
    }

    @Test
    void aMissingObjectIsFetchedAgain() throws Exception {
        downloader.run(DAY);
        raw.delete("date=2026-11-02/officers.csv");

        Downloader.Result again = downloader.run(DAY);

        assertThat(again.downloaded()).isEqualTo(1);
        assertThat(raw.exists("date=2026-11-02/officers.csv")).isTrue();
    }

    @Test
    void serverErrorsAreRetried() throws Exception {
        ur.failNext.put("/download/register.csv", new AtomicInteger(2));

        downloader.run(DAY);

        assertThat(ur.requestsFor("/download/register.csv")).isEqualTo(3);
        assertThat(raw.exists("date=2026-11-02/register.csv")).isTrue();
    }


    @Test
    void aPastCaptureIsNeverFetchedAgain() throws Exception {
        downloader.run(DAY);
        raw.delete("date=2026-11-02/officers.csv");
        Downloader later = new Downloader(new Http(HttpClient.newHttpClient(), 1, Duration.ofMillis(1)), ur.catalogUrl(), raw,
                tmp.resolve("work"), Clock.fixed(Instant.parse("2026-11-09T05:00:00Z"), ZoneOffset.UTC));
        int requests = ur.fileRequests();

        Downloader.Result again = later.run(DAY);

        assertThat(again.downloaded()).isZero();
        assertThat(again.skipped()).isEqualTo(SourceFile.values().length);
        assertThat(ur.fileRequests()).isEqualTo(requests);
        assertThat(raw.exists("date=2026-11-02/officers.csv")).isFalse();
    }

    @Test
    void anIncompletePastCaptureFails() throws Exception {
        assertThatThrownBy(() -> downloader.run(DAY.minusDays(7)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only today's capture (2026-11-02");
        assertThat(raw.exists("date=2026-10-26/manifest.json")).isFalse();
    }

    @Test
    void notFoundFailsAtOnceAndKeepsWhatWasStored() throws Exception {
        ur.gone.add("officers.csv");

        assertThatThrownBy(() -> downloader.run(DAY)).isInstanceOf(IOException.class).hasMessageContaining("HTTP 404");

        assertThat(ur.requestsFor("/download/officers.csv")).isEqualTo(1);
        Manifest manifest = Manifest.fromJson(raw.read("date=2026-11-02/manifest.json"));
        assertThat(manifest.entry(SourceFile.REGISTER)).isPresent();
        assertThat(manifest.entry(SourceFile.OFFICERS)).isEmpty();
        assertThat(raw.list("_tmp/")).isEmpty();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
