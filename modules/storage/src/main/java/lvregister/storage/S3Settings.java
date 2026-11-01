package lvregister.storage;

import java.net.URI;
import java.util.Map;

/**
 * Connection settings for the S3-compatible store (SeaweedFS). The endpoint is required:
 * nothing in this project talks to Amazon S3.
 */
public record S3Settings(URI endpoint, String region, String accessKey, String secretKey) {

    public S3Settings {
        if (endpoint == null || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("S3 endpoint must be an http(s) URL, e.g. http://lake:8333");
        }
        if (region == null || region.isBlank() || accessKey == null || accessKey.isBlank()
                || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("S3 settings need a region, an access key and a secret key");
        }
    }

    /** Reads {@code S3_ENDPOINT}, {@code S3_REGION}, {@code S3_ACCESS_KEY} and {@code S3_SECRET_KEY}. */
    public static S3Settings fromEnvironment(Map<String, String> env) {
        return new S3Settings(
                URI.create(required(env, "S3_ENDPOINT")),
                env.getOrDefault("S3_REGION", "us-east-1"),
                required(env, "S3_ACCESS_KEY"),
                required(env, "S3_SECRET_KEY"));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("environment variable " + name + " is not set");
        }
        return value;
    }

    /** Endpoint as DuckDB wants it: host and port, without the scheme. */
    public String endpointHostPort() {
        return endpoint.getHost() + (endpoint.getPort() > 0 ? ":" + endpoint.getPort() : "");
    }

    public boolean useSsl() {
        return "https".equalsIgnoreCase(endpoint.getScheme());
    }

    @Override
    public String toString() {
        return "S3Settings[endpoint=" + endpoint + ", region=" + region + ", accessKey=" + accessKey + ", secretKey=***]";
    }
}
