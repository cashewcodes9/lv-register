package lvregister.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CkanCatalogTest {

    private FakeUr ur;

    @BeforeEach
    void start() throws Exception {
        ur = new FakeUr();
    }

    @AfterEach
    void stop() {
        ur.close();
    }

    @Test
    void findsEveryMvpFileByName() {
        Map<SourceFile, CatalogEntry> catalog = CkanCatalog.parse(ur.catalogJson().getBytes(StandardCharsets.UTF_8));

        assertThat(catalog).hasSize(SourceFile.values().length);
        CatalogEntry register = catalog.get(SourceFile.REGISTER);
        assertThat(register.url().toString()).endsWith("/download/register.csv");
        assertThat(register.lastModified()).contains("2026-11-01T21:25:07");
        assertThat(register.size()).hasValue(ur.files.get("register.csv").length());
    }

    @Test
    void failsWhenAFileIsMissing() {
        ur.files.remove("officers.csv");

        assertThatIllegalStateException()
                .isThrownBy(() -> CkanCatalog.parse(ur.catalogJson().getBytes(StandardCharsets.UTF_8)))
                .withMessageContaining("officers.csv");
    }

    @Test
    void failsWhenAFileIsListedTwice() {
        String json = ur.catalogJson().replace("area_of_activity.csv", "register.csv");

        assertThatIllegalStateException()
                .isThrownBy(() -> CkanCatalog.parse(json.getBytes(StandardCharsets.UTF_8)))
                .withMessageContaining("more than once");
    }

    @Test
    void failsWhenCkanReportsNoSuccess() {
        assertThatIllegalStateException()
                .isThrownBy(() -> CkanCatalog.parse("{\"success\": false}".getBytes(StandardCharsets.UTF_8)));
    }
}
