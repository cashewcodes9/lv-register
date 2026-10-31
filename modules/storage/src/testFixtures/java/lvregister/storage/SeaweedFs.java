package lvregister.storage;

import java.net.URI;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * A SeaweedFS S3 server for tests, same image and identity setup as the compose `lake`
 * service. One container per JVM, started on first use.
 */
public final class SeaweedFs {

    public static final String ACCESS_KEY = "test-access";
    public static final String SECRET_KEY = "test-secret-key";

    private static final String S3_JSON = """
            {"identities": [{"name": "test",
              "credentials": [{"accessKey": "%s", "secretKey": "%s"}],
              "actions": ["Admin", "Read", "Write", "List", "Tagging"]}]}
            """.formatted(ACCESS_KEY, SECRET_KEY);

    private static GenericContainer<?> container;

    private SeaweedFs() {}

    @SuppressWarnings("resource") // closed by Testcontainers' Ryuk at JVM exit
    public static synchronized S3Settings settings() {
        if (container == null) {
            container = new GenericContainer<>("chrislusf/seaweedfs:4.51")
                    .withCommand("server", "-dir=/data", "-ip.bind=0.0.0.0", "-master.volumeSizeLimitMB=64",
                            "-volume.max=0", "-filer", "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json")
                    .withCopyToContainer(Transferable.of(S3_JSON), "/etc/seaweedfs/s3.json")
                    .withExposedPorts(8333)
                    .waitingFor(Wait.forHttp("/healthz").forPort(8333).withStartupTimeout(Duration.ofMinutes(2)));
            container.start();
        }
        URI endpoint = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8333));
        return new S3Settings(endpoint, "us-east-1", ACCESS_KEY, SECRET_KEY);
    }

    /** Creates a fresh bucket and returns a client for it. */
    public static S3Client createBucket(S3Client client, String bucket) {
        client.createBucket(b -> b.bucket(bucket));
        return client;
    }
}
