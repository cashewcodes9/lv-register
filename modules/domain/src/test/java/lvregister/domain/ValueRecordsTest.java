package lvregister.domain;

import static lvregister.domain.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValueRecordsTest {

    @ParameterizedTest
    @ValueSource(strings = {"40003000001", "010000001", "900000001"})
    void regNumberAcceptsNineOrElevenDigitsAndKeepsLeadingZeros(String value) {
        assertThat(new RegNumber(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "4000300000", "400030000011", "4000300000A", " 40003000001", "10000001"})
    void regNumberRejectsOtherFormats(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new RegNumber(value));
    }

    @Test
    void legalFormCodeMustBeUpperCase() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalForm("sia", LegalFamily.LLC));
        assertThat(new LegalForm("SIA", LegalFamily.LLC).code()).isEqualTo("SIA");
    }

    @Test
    void addressNeedsALineAndAPositiveVzdId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Address(" ", Optional.empty(), Optional.empty(), Optional.empty()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Address("x", Optional.empty(), Optional.of(0L), Optional.empty()));
    }

    @Test
    void moneyIsNonNegativeWithIsoCurrencyAndIgnoresScale() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(new BigDecimal("-1"), "EUR"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(BigDecimal.ONE, "eur"));
        assertThat(new Money(new BigDecimal("2800.00"), "EUR")).isEqualTo(new Money(new BigDecimal("2800"), "EUR"));
    }

    @Test
    void companyRejectsTerminationBeforeRegistration() {
        Company valid = company(REG);
        assertThatIllegalArgumentException().isThrownBy(() -> new Company(REG, "x", SIA, CompanyStatus.TERMINATED,
                Optional.empty(), Optional.of(D2), Optional.of(D1), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new Company(REG, "x", SIA, CompanyStatus.ACTIVE,
                null, Optional.empty(), Optional.empty(), Optional.empty()));
        assertThat(valid.status().isClosed()).isFalse();
    }

    @Test
    void personKeyAndNaturalPersonNeverPrintPersonData() {
        assertThat(new PersonKey("secret-key").toString()).doesNotContain("secret");
        assertThat(PERSON.toString()).doesNotContain("Test Person");
        assertThat(officer(REG).toString()).doesNotContain("Test Person");
    }

    @Test
    void jointOwnersNeedTwoFlatOwners() {
        var a = new Party.ForeignEntity("A Ltd");
        var b = new Party.ForeignEntity("B Ltd");
        assertThatIllegalArgumentException().isThrownBy(() -> new Party.JointOwners(java.util.Set.of(a)));
        var joint = new Party.JointOwners(java.util.Set.of(a, b));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Party.JointOwners(java.util.Set.of(joint, new Party.ForeignEntity("C Ltd"))));
    }

    @Test
    void officerMayHaveNeitherPositionNorGoverningBody() {
        var officer = new Role.Officer(REG, PERSON, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(officer.position()).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> new Role.Officer(REG, PERSON, null, Optional.empty(), Optional.empty()));
    }

    @Test
    void shareholderRejectsNegativeSharesAndIgnoresScale() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Role.Shareholder(REG, PERSON,
                Role.ShareRegister.MEMBERS, new BigDecimal("-1"), Optional.empty(), Optional.empty()));
        var a = new Role.Shareholder(REG, PERSON, Role.ShareRegister.MEMBERS, new BigDecimal("100.00"), Optional.empty(), Optional.empty());
        var b = new Role.Shareholder(REG, PERSON, Role.ShareRegister.MEMBERS, new BigDecimal("100"), Optional.empty(), Optional.empty());
        assertThat(a).isEqualTo(b);
        assertThat(a.shares().toPlainString()).isEqualTo("100");
        assertThat(a.shares().scale()).isZero();
        assertThat(new Money(new BigDecimal("2.50"), "EUR").amount().toPlainString()).isEqualTo("2.5");
    }

    @Test
    void beneficialOwnerRequiresAllFields() {
        assertThatNullPointerException().isThrownBy(() ->
                new Role.BeneficialOwner(REG, PERSON, null, Optional.empty(), Optional.empty()));
    }

    @Test
    void snapshotRejectsMisKeyedEntriesAndIsImmutable() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Snapshot(D1, Map.of(OTHER_REG, company(REG)), Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Snapshot(D1, Map.of(), Map.of(OTHER_REG, java.util.Set.of(officer(REG)))));
        var mutable = new java.util.HashMap<RegNumber, Company>();
        mutable.put(REG, company(REG));
        var snapshot = new Snapshot(D1, mutable, Map.of());
        mutable.clear();
        assertThat(snapshot.companies()).containsKey(REG);
        assertThat(snapshot.rolesOf(REG)).isEmpty();
    }

    @Test
    void observedBetweenMustMoveForward() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ObservedBetween(D2, D1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ObservedBetween(D1, D1));
        assertThat(new ObservedBetween(D1, LocalDate.of(2026, 11, 30)).gapDays()).isEqualTo(28);
    }

    @Test
    void cleanedCopiesAndCountsDropsAndFlags() {
        var cleaned = new Cleaned<>(List.of("a"), Map.of("missing_regcode", 2L, "bad_date", 1L), Map.of("unmapped_legal_form", 4L));
        assertThat(cleaned.droppedTotal()).isEqualTo(3);
        assertThat(cleaned.flaggedTotal()).isEqualTo(4);
        assertThatIllegalArgumentException().isThrownBy(() -> new Cleaned<>(List.of(), Map.of("x", -1L), Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Cleaned<>(List.of(), Map.of(), Map.of("x", -1L)));
    }
}
