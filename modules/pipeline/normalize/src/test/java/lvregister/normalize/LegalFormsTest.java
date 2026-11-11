package lvregister.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lvregister.domain.LegalFamily;
import lvregister.domain.LegalForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LegalFormsTest {

    /** Every `register.type` code in the 2026-10-31 capture (source assessment 3.1). */
    private static final List<String> CODES_IN_CAPTURE = List.of(
            "SIA", "ZEM", "BDR", "IK", "IND", "MIL", "KB", "PAR", "AS", "SAB", "FIL", "NOD", "AKF", "VU", "PS", "PSV",
            "DRZ", "SPO", "PAJ", "ASF", "KS", "KAT", "ARB", "SKT", "LIG", "ZVJ", "ARV", "KBU", "GIM", "PRV", "PP",
            "POR", "KSS", "UZN", "POL", "SOU", "KOR", "ROI", "SAA", "KBS", "SPA", "PPA", "ARA", "BAZ", "SE", "PAP",
            "SAV", "PRO", "EIG", "REL", "MIS", "KLO", "DIE", "RON");

    @Test
    void everyCodeInTheCaptureIsInTheTable() {
        assertThat(LegalForms.FAMILIES.keySet()).containsExactlyInAnyOrderElementsOf(CODES_IN_CAPTURE);
    }

    @ParameterizedTest
    @CsvSource({"SIA, LLC", "AS, CORPORATION", "IK, SOLE_PROPRIETORSHIP", "ZEM, SOLE_PROPRIETORSHIP",
            "PS, GENERAL_PARTNERSHIP", "KS, LIMITED_PARTNERSHIP", "KB, COOPERATIVE", "BDR, ASSOCIATION",
            "NOD, FOUNDATION", "AKF, BRANCH", "PAR, REPRESENTATION", "VU, PUBLIC_ENTERPRISE",
            "DRZ, RELIGIOUS_ORGANISATION", "PP, POLITICAL_ORGANISATION", "MIL, OTHER"})
    void mapsCodesToFamilies(String code, LegalFamily family) {
        Row row = Rows.row();

        assertThat(LegalForms.of(code, row)).contains(new LegalForm(code, family));
        assertThat(row.flags()).isEmpty();
    }

    @Test
    void unknownCodeIsOtherAndFlagged() {
        Row row = Rows.row();

        assertThat(LegalForms.of("XYZ", row)).contains(new LegalForm("XYZ", LegalFamily.OTHER));
        assertThat(row.flags()).containsExactly(LegalForms.UNMAPPED);
    }

    @Test
    void malformedCodeIsRejected() {
        assertThat(LegalForms.of("S1A", Rows.row())).isEmpty();
        assertThat(LegalForms.of("ABCDE", Rows.row())).isEmpty();
    }
}
