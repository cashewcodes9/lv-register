package lvregister.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDirectoryObjectStoreTest extends ObjectStoreContract {

    @TempDir
    Path root;

    private LocalDirectoryObjectStore store;

    @BeforeEach
    void create() {
        store = new LocalDirectoryObjectStore(root);
    }

    @Override
    ObjectStore store() {
        return store;
    }

    @Test
    void deleteRemovesEmptyDirectories() throws IOException {
        store.put("a/b/c.txt", new byte[] {1});
        store.delete("a/b/c.txt");

        try (var entries = Files.list(root)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void locationIsAnAbsolutePath() {
        assertThat(Path.of(store.location("x/y.csv"))).isEqualTo(root.toAbsolutePath().normalize().resolve("x/y.csv"));
    }
}
