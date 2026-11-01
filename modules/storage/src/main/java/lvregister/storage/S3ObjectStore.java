package lvregister.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/** Object store on one bucket of the S3-compatible store (SeaweedFS), via the S3 API. */
public final class S3ObjectStore implements ObjectStore {

    private final S3Client client;
    private final String bucket;

    public S3ObjectStore(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /** Builds an S3 protocol client for the endpoint: path-style access, checksums only where required. */
    public static S3Client client(S3Settings settings) {
        var builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                // S3-compatible stores do not all support the SDK's default request checksums.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        builder.endpointOverride(settings.endpoint()).forcePathStyle(true);
        return builder.build();
    }

    public String bucket() {
        return bucket;
    }

    @Override
    public String location(String key) {
        return "s3://" + bucket + "/" + Keys.check(key);
    }

    @Override
    public void put(String key, Path file) throws IOException {
        call(() -> client.putObject(b -> b.bucket(bucket).key(Keys.check(key)), RequestBody.fromFile(file)));
    }

    @Override
    public void put(String key, byte[] bytes) throws IOException {
        call(() -> client.putObject(b -> b.bucket(bucket).key(Keys.check(key)), RequestBody.fromBytes(bytes)));
    }

    @Override
    public InputStream open(String key) throws IOException {
        return call(() -> client.getObject(b -> b.bucket(bucket).key(Keys.check(key))));
    }

    @Override
    public Optional<Long> size(String key) throws IOException {
        try {
            return Optional.of(client.headObject(b -> b.bucket(bucket).key(Keys.check(key))).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new IOException("S3 HEAD failed for " + location(key), e);
        }
    }

    @Override
    public void copy(String fromKey, String toKey) throws IOException {
        call(() -> client.copyObject(b -> b.sourceBucket(bucket).sourceKey(Keys.check(fromKey))
                .destinationBucket(bucket).destinationKey(Keys.check(toKey))));
    }

    @Override
    public void delete(String key) throws IOException {
        call(() -> client.deleteObject(b -> b.bucket(bucket).key(Keys.check(key))));
    }

    @Override
    public long totalSize(String prefix) throws IOException {
        Keys.checkPrefix(prefix);
        return call(() -> client.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
                .mapToLong(S3Object::size)
                .sum());
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        Keys.checkPrefix(prefix);
        return call(() -> client.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
                .map(S3Object::key)
                .sorted()
                .toList());
    }

    private interface S3Call<T> {
        T run();
    }

    private static <T> T call(S3Call<T> call) throws IOException {
        try {
            return call.run();
        } catch (S3Exception e) {
            throw new IOException("S3 request failed: " + e.getMessage(), e);
        }
    }
}
