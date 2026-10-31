package io.github.cashewcodes9.lvregister.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** Small synthetic values for tests. No real persons or companies. */
final class Fixtures {

    private Fixtures() {}

    static final RegNumber REG = new RegNumber("40000000001");
    static final RegNumber OTHER_REG = new RegNumber("40000000002");
    static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    static final LocalDate D2 = LocalDate.of(2026, 11, 9);
    static final ObservedBetween OBSERVED = new ObservedBetween(D1, D2);
    static final LegalForm SIA = new LegalForm("SIA", LegalFamily.LLC);
    static final Party.NaturalPerson PERSON = new Party.NaturalPerson("Test Person", Optional.of(new PersonKey("k1")));

    static Company company(RegNumber reg) {
        return new Company(reg, "SIA \"Example\"", SIA, CompanyStatus.ACTIVE,
                Optional.of(new Address("Example iela 1, Rīga, LV-1001", Optional.of("1001"), Optional.of(100L), Optional.empty())),
                Optional.of(D1.minusYears(3)), Optional.empty(),
                Optional.of(new Money(new BigDecimal("2800.00"), "EUR")));
    }

    static Role.Officer officer(RegNumber reg) {
        return new Role.Officer(reg, PERSON, Optional.of("BOARD_MEMBER"), Optional.of("EXECUTIVE_BOARD"), Optional.of(D1));
    }
}
