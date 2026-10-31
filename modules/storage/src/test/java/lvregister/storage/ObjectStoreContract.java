package lvregister.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behaviour every {@link ObjectStore} must have. Subclasses provide an empty store. */
abstract class ObjectStoreContract {

    @TempDir
    Path tmp;

    abstract ObjectStore store();

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putReadSizeAndExists() throws IOException {
        store().put("date=2026-11-02/a.csv", bytes("a;b\n1;2\n"));

        assertThat(store().read("date=2026-11-02/a.csv")).isEqualTo(bytes("a;b\n1;2\n"));
        assertThat(store().size("date=2026-11-02/a.csv")).contains(8L);
        assertThat(store().exists("date=2026-11-02/a.csv")).isTrue();
        assertThat(store().exists("date=2026-11-02/missing.csv")).isFalse();
        assertThat(store().size("missing")).isEmpty();
    }

    @Test
    void putFromFileAndOverwrite() throws IOException {
        Path file = Files.write(tmp.resolve("f"), bytes("first"));
        store().put("x/f.txt", file);
        store().put("x/f.txt", bytes("second"));

        assertThat(store().read("x/f.txt")).isEqualTo(bytes("second"));
    }

    @Test
    void listIsSortedAndFiltersByPrefix() throws IOException {
        store().put("date=2026-11-09/b.csv", bytes("1"));
        store().put("date=2026-11-02/b.csv", bytes("1"));
        store().put("date=2026-11-02/a.csv", bytes("1"));
        store().put("other/c.csv", bytes("1"));

        assertThat(store().list("date=2026-11-02/")).containsExactly("date=2026-11-02/a.csv", "date=2026-11-02/b.csv");
        assertThat(store().list("date=")).hasSize(3);
        assertThat(store().list("")).hasSize(4);
        assertThat(store().list("nothing/")).isEmpty();
    }

    @Test
    void copyAndDelete() throws IOException {
        store().put("a", bytes("data"));
        store().copy("a", "dir/b");
        store().delete("a");
        store().delete("a");

        assertThat(store().exists("a")).isFalse();
        assertThat(store().read("dir/b")).isEqualTo(bytes("data"));
    }

    @Test
    void putAtomicallyLeavesNoTemporaryObjects() throws IOException {
        Path file = Files.write(tmp.resolve("f"), bytes("payload"));
        store().putAtomically("date=2026-11-02/register.csv", file);
        store().putAtomically("date=2026-11-02/manifest.json", bytes("{}"));

        assertThat(store().list("")).containsExactly("date=2026-11-02/manifest.json", "date=2026-11-02/register.csv");
        assertThat(store().read("date=2026-11-02/register.csv")).isEqualTo(bytes("payload"));
    }

    @Test
    void readingAMissingObjectFails() {
        assertThatThrownBy(() -> store().read("missing")).isInstanceOf(IOException.class);
    }

    @Test
    void unsafeKeysAreRejected() {
        for (String key : new String[] {"", "/abs", "a/../b", "a//b", "dir/", "spa ce", "a/./b"}) {
            assertThatIllegalArgumentException().as(key).isThrownBy(() -> store().put(key, bytes("x")));
        }
    }

    @Test
    void locationNamesTheObject() {
        assertThat(store().location("date=2026-11-02/register.csv")).endsWith("date=2026-11-02/register.csv");
    }
}
