package lvregister.lake;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import lvregister.storage.ObjectStore;
import lvregister.storage.S3ObjectStore;
import lvregister.storage.S3Settings;
import lvregister.storage.SeaweedFs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;

/** The lake step against SeaweedFS: DuckDB reads and writes s3:// through httpfs. */
class S3LakeTest extends LakeContract {

    private static S3Settings settings;
    private static S3Client client;

    @TempDir
    Path tmp;

    private ObjectStore raw;
    private ObjectStore lake;
    private DuckDb db;

    @BeforeAll
    static void connect() {
        settings = SeaweedFs.settings();
        client = S3ObjectStore.client(settings);
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void open() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        raw = new S3ObjectStore(SeaweedFs.createBucket(client, "raw-" + suffix), "raw-" + suffix);
        lake = new S3ObjectStore(SeaweedFs.createBucket(client, "lake-" + suffix), "lake-" + suffix);
        db = DuckDb.open(new DuckDbSettings("512MB", 1, tmp.resolve("duckdb"), Optional.empty()), Optional.of(settings));
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    @Override
    ObjectStore raw() {
        return raw;
    }

    @Override
    ObjectStore lake() {
        return lake;
    }

    @Override
    DuckDb db() {
        return db;
    }

    @Override
    java.nio.file.Path workDir() {
        return tmp.resolve("work");
    }
}
