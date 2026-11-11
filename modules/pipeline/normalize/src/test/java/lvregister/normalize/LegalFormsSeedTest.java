package lvregister.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The dbt seed transform/seeds/legal_forms.csv must hold the same mapping as {@link LegalForms}. */
class LegalFormsSeedTest {

    @Test
    void dbtSeedEqualsTheJavaTable() throws Exception {
        Map<String, String> seed = Files.readAllLines(Path.of("../../../transform/seeds/legal_forms.csv")).stream()
                .skip(1)
                .filter(line -> !line.isBlank())
                .map(line -> line.split(","))
                .collect(Collectors.toMap(p -> p[0], p -> p[1]));

        assertThat(seed).isEqualTo(LegalForms.FAMILIES.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().name())));
    }
}
