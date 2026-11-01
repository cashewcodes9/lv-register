package lvregister.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import org.junit.jupiter.api.Test;

class S3SettingsTest {

    @Test
    void readsTheEnvironment() {
        S3Settings s = S3Settings.fromEnvironment(Map.of(
                "S3_ENDPOINT", "http://lake:8333", "S3_ACCESS_KEY", "k", "S3_SECRET_KEY", "s"));

        assertThat(s.region()).isEqualTo("us-east-1");
        assertThat(s.endpointHostPort()).isEqualTo("lake:8333");
        assertThat(s.useSsl()).isFalse();
        assertThat(s.toString()).endsWith("secretKey=***]");
    }

    @Test
    void endpointIsRequired() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> S3Settings.fromEnvironment(Map.of("S3_ACCESS_KEY", "k", "S3_SECRET_KEY", "s")))
                .withMessageContaining("S3_ENDPOINT");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> S3Settings.fromEnvironment(Map.of(
                        "S3_ENDPOINT", "lake:8333", "S3_ACCESS_KEY", "k", "S3_SECRET_KEY", "s")));
    }

    @Test
    void keysAreRequired() {
        assertThatIllegalArgumentException().isThrownBy(() -> S3Settings.fromEnvironment(Map.of("S3_ENDPOINT", "http://lake:8333")));
    }
}
