package lvregister.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void normalizesToNfcAndCollapsesWhitespace() {
        assertThat(Text.clean("  SIA  \"Rīga\"  Test \t")).contains("SIA \"Rīga\" Test");
    }

    @Test
    void blankIsMissing() {
        assertThat(Text.clean(" ")).isEmpty();
        assertThat(Text.clean(null)).isEmpty();
    }

    @Test
    void codesAreUpperCase() {
        assertThat(Text.code(" board_member ")).contains("BOARD_MEMBER");
    }
}
