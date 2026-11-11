package lvregister.lake;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Resource limits for DuckDB, sized for the 1 CPU / 4 GB dev VM by default.
 *
 * @param memoryLimit         DuckDB {@code memory_limit}, e.g. {@code 1GB}
 * @param threads             DuckDB {@code threads}
 * @param tempDirectory       where DuckDB spills when over the memory limit
 * @param extensionDirectory  pre-installed extensions (the cli image ships httpfs there);
 *                            empty means DuckDB's default under the user's home
 */
public record DuckDbSettings(String memoryLimit, int threads, Path tempDirectory, Optional<Path> extensionDirectory) {

    public DuckDbSettings {
        if (memoryLimit == null || !memoryLimit.matches("\\d+(\\.\\d+)?\\s*(KB|MB|GB|KiB|MiB|GiB)")) {
            throw new IllegalArgumentException("memoryLimit must look like 1GB or 512MB");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1");
        }
        if (tempDirectory == null || extensionDirectory == null) {
            throw new IllegalArgumentException("directories must be set");
        }
    }

    /** {@code DUCKDB_MEMORY_LIMIT}, {@code DUCKDB_THREADS}, {@code DUCKDB_TEMP_DIRECTORY}, {@code DUCKDB_EXTENSION_DIRECTORY}. */
    public static DuckDbSettings fromEnvironment(Map<String, String> env) {
        return new DuckDbSettings(
                env.getOrDefault("DUCKDB_MEMORY_LIMIT", "1GB"),
                Integer.parseInt(env.getOrDefault("DUCKDB_THREADS", "1")),
                Path.of(env.getOrDefault("DUCKDB_TEMP_DIRECTORY", System.getProperty("java.io.tmpdir") + "/duckdb")),
                Optional.ofNullable(env.get("DUCKDB_EXTENSION_DIRECTORY")).filter(s -> !s.isBlank()).map(Path::of));
    }
}
