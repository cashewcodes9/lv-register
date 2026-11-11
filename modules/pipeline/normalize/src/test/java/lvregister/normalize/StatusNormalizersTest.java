package lvregister.normalize;

import static lvregister.normalize.Rows.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lvregister.domain.Cleaned;
import lvregister.domain.Money;
import lvregister.domain.RegNumber;
import org.junit.jupiter.api.Test;

class StatusNormalizersTest {

    private static final RegNumber A = new RegNumber("40003000001");
    private static final RegNumber B = new RegNumber("40003000002");

    @Test
    void capitalPrefersRegisteredOverPaidUpAndIgnoresOtherTypes() {
        Cleaned<EquityCapital> equity = StatusNormalizers.equityCapitals(Stream.of(
                row("legal_entity_registration_number", A.value(), "equity_capital_type", "PAID_UP_EQUITY", "amount", "1000", "currency", "EUR"),
                row("legal_entity_registration_number", A.value(), "equity_capital_type", "REGISTERED_EQUITY", "amount", "2000.00", "currency", "EUR"),
                row("legal_entity_registration_number", B.value(), "equity_capital_type", "INVESTMENT", "amount", "5", "currency", "EUR"),
                row("legal_entity_registration_number", B.value(), "equity_capital_type", "PAID_UP_EQUITY", "amount", "-1", "currency", "EUR"),
                row("legal_entity_registration_number", B.value(), "equity_capital_type", "PAID_UP_EQUITY", "amount", "1")));

        assertThat(StatusNormalizers.capital(equity.rows())).containsExactly(Map.entry(A, new Money(new BigDecimal("2000"), "EUR")));
        assertThat(equity.droppedByRule()).containsOnly(Map.entry("equity.invalid_amount", 1L), Map.entry("equity.missing_currency", 1L));
    }

    @Test
    void openProceedingsCombineLiquidationsAndOpenInsolvencies() {
        Cleaned<Liquidation> liquidations = StatusNormalizers.liquidations(Stream.of(
                row("legal_entity_registration_number", A.value(), "liquidation_type", "LIQUIDATION", "date_from", "2026-01-02")));
        Cleaned<Proceeding> proceedings = StatusNormalizers.proceedings(Stream.of(
                row("debtor_registration_number", A.value(), "proceeding_form", "INSOLVENCY", "proceeding_started_on", "2026-02-01"),
                row("debtor_registration_number", B.value(), "proceeding_form", "LEGAL_PROTECTION", "proceeding_ended_on", "2020-01-01"),
                row("debtor_registration_number", B.value(), "proceeding_form", "NEW_FORM"),
                row("debtor_registration_number", "123", "proceeding_form", "INSOLVENCY")));

        assertThat(StatusNormalizers.openProceedings(liquidations.rows(), proceedings.rows())).containsOnly(
                Map.entry(A, new OpenProceedings(true, Set.of("INSOLVENCY"))),
                Map.entry(B, new OpenProceedings(false, Set.of("NEW_FORM"))));
        assertThat(proceedings.flaggedByRule()).containsOnly(Map.entry(Statuses.UNMAPPED_FORM, 1L));
        assertThat(proceedings.droppedByRule()).containsOnly(Map.entry("insolvency.invalid_regcode", 1L));
        assertThat(liquidations.rows().getFirst().since()).isPresent();
    }

    @Test
    void reorganizationsNeedBothRegistrationNumbers() {
        Cleaned<Reorganization> cleaned = StatusNormalizers.reorganizations(Stream.of(
                row("reorganization_type", "ACQUISITION", "source_entity_regcode", A.value(), "final_entity_regcode", B.value(),
                        "registered", "2015-03-04"),
                row("reorganization_type", "MERGER", "source_entity_regcode", A.value()),
                row("reorganization_type", "ACQUISITION", "source_entity_regcode", "1000123456", "final_entity_regcode", B.value())));

        assertThat(cleaned.rows()).extracting(Reorganization::type, Reorganization::target).containsExactly(
                org.assertj.core.groups.Tuple.tuple("ACQUISITION", B));
        assertThat(cleaned.droppedByRule()).containsOnly(Map.entry("reorganizations.invalid_regcode", 1L),
                Map.entry(StatusNormalizers.LEGACY_REGCODE, 1L));
        assertThat(cleaned.rows().getFirst().registered()).isNotEqualTo(Optional.empty());
        assertThat(List.of(cleaned.rows().getFirst().source())).containsExactly(A);
    }
}
