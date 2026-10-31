package lvregister.normalize;

import static lvregister.normalize.Rows.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lvregister.domain.Address;
import lvregister.domain.Cleaned;
import lvregister.domain.Company;
import lvregister.domain.CompanyStatus;
import lvregister.domain.LegalFamily;
import lvregister.domain.LegalForm;
import lvregister.domain.Money;
import lvregister.domain.RegNumber;
import org.junit.jupiter.api.Test;

class RegisterNormalizerTest {

    private static final RegNumber REG = new RegNumber("40003000001");

    private static Row company(String regcode, String type, String closed) {
        return row("regcode", regcode, "name", " SIA  \"Paraugs\" ", "type", type, "registered", "2010-05-04",
                "closed", closed, "address", "Rīga, Testa iela 1", "index", "1050", "addressid", "100", "atvk", "0001000");
    }

    private static Cleaned<Company> normalize(Row... rows) {
        return RegisterNormalizer.normalize(Stream.of(rows),
                Map.of(REG, new OpenProceedings(true, Set.of())), Map.of(REG, new Money(new BigDecimal("2800.00"), "EUR")));
    }

    @Test
    void buildsACompanyWithStatusAndCapital() {
        Cleaned<Company> cleaned = normalize(company("40003000001", "SIA", " "));

        assertThat(cleaned.rows()).containsExactly(new Company(REG, "SIA \"Paraugs\"", new LegalForm("SIA", LegalFamily.LLC),
                CompanyStatus.IN_LIQUIDATION,
                Optional.of(new Address("Rīga, Testa iela 1", Optional.of("1050"), Optional.of(100L), Optional.of("0001000"))),
                Optional.of(LocalDate.of(2010, 5, 4)), Optional.empty(), Optional.of(new Money(new BigDecimal("2800"), "EUR"))));
        assertThat(cleaned.droppedByRule()).isEmpty();
        assertThat(cleaned.flaggedByRule()).isEmpty();
    }

    @Test
    void keepsLeadingZerosOfOldRegistrationNumbers() {
        assertThat(normalize(company("012345678", "IK", " ")).rows()).extracting(c -> c.regNumber().value())
                .containsExactly("012345678");
    }

    @Test
    void dropsBrokenRowsAndCountsThem() {
        Cleaned<Company> cleaned = normalize(
                company("4000300000", "SIA", " "),
                row("regcode", "40003000002", "type", "SIA"),
                company("40003000003", "S1A", " "),
                company("40003000001", "SIA", " "),
                company("40003000001", "AS", " "),
                row("regcode", "40003000004", "name", "X", "type", "SIA", "registered", "2010-01-02", "terminated", "2009-01-01"));

        assertThat(cleaned.rows()).extracting(Company::regNumber).containsExactly(REG);
        assertThat(cleaned.droppedByRule()).containsOnly(
                Map.entry(RegisterNormalizer.INVALID_REGCODE, 1L),
                Map.entry(RegisterNormalizer.MISSING_NAME, 1L),
                Map.entry(RegisterNormalizer.INVALID_LEGAL_FORM, 1L),
                Map.entry(RegisterNormalizer.DUPLICATE_REGCODE, 1L),
                Map.entry(RegisterNormalizer.TERMINATED_BEFORE_REGISTERED, 1L));
    }

    @Test
    void flagsUnknownValuesInsteadOfDroppingThem() {
        Row row = row("regcode", "40003000005", "name", "X", "type", "XYZ", "registered", "2010-13-01", "closed", " ",
                "address", "Rīga", "index", "LV-1050", "addressid", "abc");

        Cleaned<Company> cleaned = normalize(row);

        Company c = cleaned.rows().getFirst();
        assertThat(c.legalForm().family()).isEqualTo(LegalFamily.OTHER);
        assertThat(c.registered()).isEmpty();
        assertThat(c.address()).contains(new Address("Rīga", Optional.empty(), Optional.empty(), Optional.empty()));
        assertThat(cleaned.flaggedByRule()).containsOnly(
                Map.entry(LegalForms.UNMAPPED, 1L),
                Map.entry(RegisterNormalizer.INVALID_DATE, 1L),
                Map.entry(RegisterNormalizer.INVALID_POSTAL_CODE, 1L),
                Map.entry(RegisterNormalizer.INVALID_ADDRESS_ID, 1L));
    }

    @Test
    void unknownAddressIdZeroAndMissingAddressAreEmpty() {
        Row zero = row("regcode", "40003000006", "name", "X", "type", "SIA", "closed", " ", "address", "Rīga", "addressid", "0");
        Row none = row("regcode", "40003000007", "name", "X", "type", "SIA", "closed", "L", "terminated", "2020-01-01");

        Cleaned<Company> cleaned = normalize(zero, none);

        assertThat(cleaned.rows().get(0).address().flatMap(Address::vzdId)).isEmpty();
        assertThat(cleaned.rows().get(1).address()).isEmpty();
        assertThat(cleaned.rows().get(1).status()).isEqualTo(CompanyStatus.TERMINATED);
        assertThat(cleaned.flaggedByRule()).isEmpty();
    }
}
