package lvregister.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/** The contract against a real SeaweedFS S3 API (Testcontainers). */
class S3ObjectStoreTest extends ObjectStoreContract {

    private static S3Client client;
    private S3ObjectStore store;

    @BeforeAll
    static void connect() {
        client = S3ObjectStore.client(SeaweedFs.settings());
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void freshBucket() {
        String bucket = "t-" + UUID.randomUUID().toString().substring(0, 8);
        SeaweedFs.createBucket(client, bucket);
        store = new S3ObjectStore(client, bucket);
    }

    @Override
    ObjectStore store() {
        return store;
    }

    @Test
    void locationIsAnS3Uri() {
        assertThat(store.location("date=2026-11-02/register.csv"))
                .isEqualTo("s3://" + store.bucket() + "/date=2026-11-02/register.csv");
    }
}
