package lvregister.ingest;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One stored raw file.
 *
 * @param file                 CSV file name, e.g. {@code register.csv}
 * @param url                  where it was downloaded from
 * @param sha256               hex digest of the stored bytes
 * @param size                 bytes
 * @param fetchedAt            when the download finished
 * @param sourceLastModified   CKAN {@code last_modified} of the resource, as published
 */
public record ManifestEntry(
        String file, URI url, String sha256, long size, Instant fetchedAt, Optional<String> sourceLastModified) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ManifestEntry {
        if (file == null || file.isBlank() || url == null || fetchedAt == null || sourceLastModified == null) {
            throw new IllegalArgumentException("manifest entry fields must be set");
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lower-case hex characters");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }
}
