package lvregister.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import lvregister.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code download} step. For one capture date: look up the MVP files in the CKAN catalog,
 * download each one that this capture does not have yet, computing sha256 while streaming,
 * store it under {@code date=D/<file>.csv} through a temporary key, and record it in
 * {@code date=D/manifest.json}.
 *
 * <p>Idempotent: a file already in the capture's manifest, with an object of the recorded
 * size, is not downloaded again. A capture is fixed once written; re-running it never
 * replaces a file with newer content.
 */
public final class Downloader {

    private static final Logger LOG = LoggerFactory.getLogger(Downloader.class);

    private final Http http;
    private final URI catalogUrl;
    private final ObjectStore raw;
    private final Path workDir;
    private final Clock clock;

    public Downloader(Http http, URI catalogUrl, ObjectStore raw, Path workDir, Clock clock) {
        this.http = http;
        this.catalogUrl = catalogUrl;
        this.raw = raw;
        this.workDir = workDir;
        this.clock = clock;
    }

    /** Result counts, for logs and the run record. */
    public record Result(int downloaded, int skipped, long bytesDownloaded) {}

    public Result run(LocalDate captureDate) throws IOException {
        Manifest manifest = readManifest(captureDate);
        Map<SourceFile, CatalogEntry> catalog = CkanCatalog.parse(http.getBytes(catalogUrl));
        int downloaded = 0;
        int skipped = 0;
        long bytes = 0;
        for (SourceFile file : SourceFile.values()) {
            String key = RawLayout.csvKey(captureDate, file);
            Optional<ManifestEntry> existing = manifest.entry(file);
            if (existing.isPresent() && raw.size(key).equals(Optional.of(existing.get().size()))) {
                LOG.info("skip {}: already in capture {}", file.csvName(), captureDate);
                skipped++;
                continue;
            }
            ManifestEntry entry = fetch(catalog.get(file), key);
            manifest = manifest.with(entry);
            raw.putAtomically(RawLayout.manifestKey(captureDate), manifest.toJson());
            LOG.info("stored {}: {} bytes, sha256 {}", file.csvName(), entry.size(), entry.sha256());
            downloaded++;
            bytes += entry.size();
        }
        return new Result(downloaded, skipped, bytes);
    }

    private Manifest readManifest(LocalDate captureDate) throws IOException {
        String key = RawLayout.manifestKey(captureDate);
        if (!raw.exists(key)) {
            return Manifest.empty(captureDate);
        }
        Manifest manifest = Manifest.fromJson(raw.read(key));
        if (!manifest.captureDate().equals(captureDate)) {
            throw new IllegalStateException("manifest under " + key + " is for " + manifest.captureDate());
        }
        return manifest;
    }

    private ManifestEntry fetch(CatalogEntry source, String key) throws IOException {
        Files.createDirectories(workDir);
        Path tmp = Files.createTempFile(workDir, source.file().baseName() + "-", ".part");
        try {
            MessageDigest sha = sha256();
            long size;
            try (InputStream in = new DigestInputStream(http.open(source.url()), sha);
                    OutputStream out = Files.newOutputStream(tmp)) {
                size = in.transferTo(out);
            }
            source.size().ifPresent(expected -> {
                if (expected != size) {
                    LOG.warn("{}: downloaded {} bytes, CKAN lists {}", source.file().csvName(), size, expected);
                }
            });
            raw.putAtomically(key, tmp);
            return new ManifestEntry(source.file().csvName(), source.url(), HexFormat.of().formatHex(sha.digest()),
                    size, clock.instant(), source.lastModified());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
