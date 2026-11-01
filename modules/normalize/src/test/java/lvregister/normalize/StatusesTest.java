package lvregister.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import lvregister.domain.CompanyStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatusesTest {

    private static final Optional<LocalDate> NOT_TERMINATED = Optional.empty();

    @ParameterizedTest(name = "closed=[{0}] liquidation={1} forms={2} -> {3}")
    @CsvSource(delimiter = '|', value = {
            "L| true | INSOLVENCY       | TERMINATED",
            "R| false|                  | REORGANIZED",
            " | true | INSOLVENCY       | INSOLVENT",
            " | false| LEGAL_PROTECTION | LEGAL_PROTECTION",
            " | false| OUT_OF_COURT_LEGAL_PROTECTION | LEGAL_PROTECTION",
            " | true | LEGAL_PROTECTION | LEGAL_PROTECTION",
            " | true |                  | IN_LIQUIDATION",
            " | false|                  | ACTIVE"})
    void precedence(String closed, boolean liquidation, String form, CompanyStatus expected) {
        OpenProceedings open = new OpenProceedings(liquidation, form == null ? Set.of() : Set.of(form));
        Row row = Rows.row();

        assertThat(Statuses.of(Optional.of(closed == null ? " " : closed), NOT_TERMINATED, open, row)).isEqualTo(expected);
        assertThat(row.flags()).isEmpty();
    }

    @Test
    void terminationDateWithoutClosedFlagIsTerminatedAndFlagged() {
        Row row = Rows.row();

        assertThat(Statuses.of(Optional.of(" "), Optional.of(LocalDate.of(2001, 5, 4)), OpenProceedings.NONE, row))
                .isEqualTo(CompanyStatus.TERMINATED);
        assertThat(row.flags()).containsExactly(Statuses.TERMINATED_WITHOUT_FLAG);
    }

    @Test
    void unknownClosedCodeIsFlagged() {
        Row row = Rows.row();

        assertThat(Statuses.of(Optional.of("X"), NOT_TERMINATED, OpenProceedings.NONE, row)).isEqualTo(CompanyStatus.ACTIVE);
        assertThat(row.flags()).containsExactly(Statuses.UNMAPPED_CLOSED);
    }

    @Test
    void missingClosedMeansActive() {
        assertThat(Statuses.of(Optional.empty(), NOT_TERMINATED, OpenProceedings.NONE, Rows.row())).isEqualTo(CompanyStatus.ACTIVE);
    }
}
