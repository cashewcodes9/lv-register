package lvregister.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A flat key-value store for files, one per bucket. Keys use {@code /} as separator, for
 * example {@code date=2026-11-02/register.csv}.
 */
public interface ObjectStore {

    /** Prefix for temporary keys used by {@link #putAtomically}. */
    String TMP_PREFIX = "_tmp/";

    /** Where DuckDB can read or write the object: {@code s3://bucket/key} or a local path. */
    String location(String key);

    void put(String key, Path file) throws IOException;

    void put(String key, byte[] bytes) throws IOException;

    /** Opens the object for reading. The caller closes the stream. */
    InputStream open(String key) throws IOException;

    default byte[] read(String key) throws IOException {
        try (InputStream in = open(key)) {
            return in.readAllBytes();
        }
    }

    /** Size in bytes, or empty when the object does not exist. */
    Optional<Long> size(String key) throws IOException;

    default boolean exists(String key) throws IOException {
        return size(key).isPresent();
    }

    void copy(String fromKey, String toKey) throws IOException;

    /** Deletes the object; does nothing when it does not exist. */
    void delete(String key) throws IOException;

    /** Total size in bytes of the objects whose key starts with the prefix. */
    long totalSize(String prefix) throws IOException;

    /** Keys that start with the prefix, sorted. */
    List<String> list(String prefix) throws IOException;

    /**
     * Writes the file under a temporary key and then copies it into place, so a failed run
     * never leaves a half-written object under the final key.
     */
    default void putAtomically(String key, Path file) throws IOException {
        String tmp = TMP_PREFIX + UUID.randomUUID() + "/" + Keys.check(key);
        put(tmp, file);
        try {
            copy(tmp, key);
        } finally {
            delete(tmp);
        }
    }

    /** As {@link #putAtomically(String, Path)} for small objects such as manifests. */
    default void putAtomically(String key, byte[] bytes) throws IOException {
        String tmp = TMP_PREFIX + UUID.randomUUID() + "/" + Keys.check(key);
        put(tmp, bytes);
        try {
            copy(tmp, key);
        } finally {
            delete(tmp);
        }
    }
}
