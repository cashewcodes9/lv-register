package lvregister.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Object store on a local directory: key {@code a/b.csv} is the file {@code <root>/a/b.csv}. */
public final class LocalDirectoryObjectStore implements ObjectStore {

    private final Path root;

    public LocalDirectoryObjectStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private Path path(String key) {
        return root.resolve(Keys.check(key));
    }

    @Override
    public String location(String key) {
        return path(key).toString();
    }

    @Override
    public void put(String key, Path file) throws IOException {
        Path target = path(key);
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void put(String key, byte[] bytes) throws IOException {
        Path target = path(key);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(path(key));
    }

    @Override
    public Optional<Long> size(String key) throws IOException {
        Path p = path(key);
        return Files.isRegularFile(p) ? Optional.of(Files.size(p)) : Optional.empty();
    }

    @Override
    public void copy(String fromKey, String toKey) throws IOException {
        Path target = path(toKey);
        Files.createDirectories(target.getParent());
        Files.copy(path(fromKey), target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            Files.delete(path(key));
        } catch (NoSuchFileException e) {
            return;
        }
        deleteEmptyParents(path(key).getParent());
    }

    private void deleteEmptyParents(Path dir) throws IOException {
        Path current = dir;
        while (current != null && !current.equals(root) && current.startsWith(root)) {
            try (Stream<Path> entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
            Files.delete(current);
            current = current.getParent();
        }
    }

    @Override
    public long totalSize(String prefix) throws IOException {
        long total = 0;
        for (String key : list(prefix)) {
            total += Files.size(path(key));
        }
        return total;
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        Keys.checkPrefix(prefix);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(key -> key.startsWith(prefix))
                    .sorted()
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
