package lvregister.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** GET with retries on network errors and 5xx responses. */
public final class Http {

    private static final Logger LOG = LoggerFactory.getLogger(Http.class);

    private final HttpClient client;
    private final int attempts;
    private final Duration backoff;

    public Http(HttpClient client, int attempts, Duration backoff) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1");
        }
        this.client = client;
        this.attempts = attempts;
        this.backoff = backoff;
    }

    public static Http defaults() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return new Http(client, 3, Duration.ofSeconds(5));
    }

    /** Reads a small response fully (the CKAN catalog). */
    public byte[] getBytes(URI uri) throws IOException {
        try (InputStream in = open(uri)) {
            return in.readAllBytes();
        }
    }

    /** Opens a streaming GET. The caller closes the stream. 4xx fails at once; 5xx and I/O errors are retried. */
    public InputStream open(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15)).GET().build();
        IOException last = new IOException("GET " + uri + " was not attempted");
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                LOG.warn("attempt {} of {} failed for {}: {}", attempt - 1, attempts, uri, last.getMessage());
                sleep(backoff.multipliedBy(attempt - 1));
            }
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while fetching " + uri, e);
            } catch (IOException e) {
                last = e;
                continue;
            }
            int status = response.statusCode();
            if (status == 200) {
                return response.body();
            }
            response.body().close();
            last = new IOException("GET " + uri + " returned HTTP " + status);
            if (status < 500) {
                throw last;
            }
        }
        throw last;
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during retry backoff", e);
        }
    }
}
