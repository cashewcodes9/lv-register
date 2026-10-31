package lvregister.lake;

import java.nio.file.Path;
import java.util.Optional;
import lvregister.storage.LocalDirectoryObjectStore;
import lvregister.storage.ObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** The lake step on local directories (no httpfs). */
class LocalLakeTest extends LakeContract {

    @TempDir
    Path tmp;

    private ObjectStore raw;
    private ObjectStore lake;
    private DuckDb db;

    @BeforeEach
    void open() throws Exception {
        raw = new LocalDirectoryObjectStore(tmp.resolve("raw"));
        lake = new LocalDirectoryObjectStore(tmp.resolve("lake"));
        db = DuckDb.open(new DuckDbSettings("512MB", 1, tmp.resolve("duckdb"), Optional.empty()), Optional.empty());
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
