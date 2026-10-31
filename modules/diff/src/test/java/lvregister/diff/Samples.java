package lvregister.diff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import lvregister.domain.Address;
import lvregister.domain.Company;
import lvregister.domain.CompanyStatus;
import lvregister.domain.LegalFamily;
import lvregister.domain.LegalForm;
import lvregister.domain.Money;
import lvregister.domain.Party;
import lvregister.domain.PersonKey;
import lvregister.domain.RegNumber;
import lvregister.domain.Role;

/** Synthetic values for diff tests. No real data. */
final class Samples {

    static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    static final LocalDate D2 = LocalDate.of(2026, 11, 9);
    static final RegNumber REG = new RegNumber("40003000001");
    static final LegalForm SIA = new LegalForm("SIA", LegalFamily.LLC);
    static final LegalForm AS = new LegalForm("AS", LegalFamily.CORPORATION);
    static final Party PERSON = new Party.NaturalPerson("Test Person", Optional.of(new PersonKey("k".repeat(8))));
    static final Party OTHER_PERSON = new Party.NaturalPerson("Other Person", Optional.empty());

    private Samples() {}

    static Address address(String line) {
        return new Address(line, Optional.of("1050"), Optional.of(100L), Optional.empty());
    }

    static Company company(RegNumber reg, String name, LegalForm form, CompanyStatus status, Optional<LocalDate> terminated) {
        return new Company(reg, name, form, status, Optional.of(address("Rīga, Testa iela 1")), Optional.of(LocalDate.of(2010, 1, 2)),
                terminated, Optional.of(new Money(new BigDecimal("2800"), "EUR")));
    }

    static Company active(String name) {
        return company(REG, name, SIA, CompanyStatus.ACTIVE, Optional.empty());
    }

    static Role.Officer officer(Party holder, String position) {
        return new Role.Officer(REG, holder, Optional.of(position), Optional.of("EXECUTIVE_BOARD"), Optional.of(LocalDate.of(2020, 1, 1)));
    }

    static Role.Shareholder member(Party holder, String shares) {
        return new Role.Shareholder(REG, holder, Role.ShareRegister.MEMBERS, new BigDecimal(shares),
                Optional.of(new Money(BigDecimal.ONE, "EUR")), Optional.empty());
    }
}
