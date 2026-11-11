package lvregister.ingest;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A local stand-in for data.gov.lv: a CKAN package_search answer and one small CSV per file. */
final class FakeUr implements AutoCloseable {

    private final HttpServer server;
    final Map<String, String> files = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    /** Answer the next N requests for a path with HTTP 503. */
    final Map<String, AtomicInteger> failNext = new ConcurrentHashMap<>();
    /** Files listed in the catalog whose download returns 404. */
    final java.util.Set<String> gone = ConcurrentHashMap.newKeySet();

    FakeUr() throws IOException {
        for (SourceFile file : SourceFile.values()) {
            files.put(file.csvName(), "id;name\n1;" + file.baseName() + "\n");
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            AtomicInteger failures = failNext.get(path);
            byte[] body;
            int status;
            if (failures != null && failures.getAndDecrement() > 0) {
                status = 503;
                body = new byte[0];
            } else if (path.equals("/api/package_search")) {
                status = 200;
                body = catalogJson().getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/download/") && files.containsKey(path.substring("/download/".length()))
                    && !gone.contains(path.substring("/download/".length()))) {
                status = 200;
                body = files.get(path.substring("/download/".length())).getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                body = new byte[0];
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    URI catalogUrl() {
        return URI.create(base() + "/api/package_search");
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requestsFor(String path) {
        AtomicInteger n = requests.get(path);
        return n == null ? 0 : n.get();
    }

    int fileRequests() {
        return requests.entrySet().stream().filter(e -> e.getKey().startsWith("/download/"))
                .mapToInt(e -> e.getValue().get()).sum();
    }

    String catalogJson() {
        String resources = Stream.concat(files.keySet().stream(), Stream.of("area_of_activity.csv"))
                .sorted()
                .map(name -> """
                        {"url": "%s/download/%s", "format": "CSV", "last_modified": "2026-11-01T21:25:07", "size": %d}"""
                        .formatted(base(), name, files.getOrDefault(name, "").length()))
                .collect(Collectors.joining(","));
        return """
                {"success": true, "result": {"count": 1, "results": [{"name": "ur", "resources": [%s]}]}}"""
                .formatted(resources);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
