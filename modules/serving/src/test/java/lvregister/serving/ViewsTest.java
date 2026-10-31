package lvregister.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.junit.jupiter.api.Test;

class ViewsTest {

    private static final RegNumber REG = new RegNumber("40003000001");
    private static final LocalDate D = LocalDate.of(2026, 11, 9);
    private static final Party PERSON = new Party.NaturalPerson("Test Person", Optional.of(new PersonKey("abc")));
    private static final Party ENTITY = new Party.LatvianEntity(new RegNumber("40003999999"), "SIA \"Holder\"");

    private static Company company() {
        return new Company(REG, "SIA \"Alfa\"", new LegalForm("SIA", LegalFamily.LLC), CompanyStatus.ACTIVE,
                Optional.of(new Address("Rīga, Testa iela 1", Optional.of("1050"), Optional.of(100L), Optional.empty())),
                Optional.of(LocalDate.of(2010, 1, 2)), Optional.empty(), Optional.of(new Money(new BigDecimal("2800.00"), "EUR")));
    }

    @Test
    void mapsEveryRoleKind() {
        Set<Role> roles = Set.of(
                new Role.Officer(REG, PERSON, Optional.of("BOARD_MEMBER"), Optional.empty(), Optional.of(D)),
                new Role.Shareholder(REG, new Party.JointOwners(Set.of(PERSON, ENTITY)), Role.ShareRegister.MEMBERS,
                        new BigDecimal("100.00"), Optional.empty(), Optional.empty()),
                new Role.BeneficialOwner(REG, (Party.NaturalPerson) PERSON, Set.of("US", "LV"), Optional.of("LV"), Optional.empty()));

        CompanyView view = Views.company(company(), roles, D);

        assertThat(view.regNumber()).isEqualTo("40003000001");
        assertThat(view.legalFamily()).isEqualTo("LLC");
        assertThat(view.capital()).contains(new CompanyView.MoneyView("2800", "EUR"));
        assertThat(view.roles()).extracting(CompanyView.RoleView::kind)
                .containsExactlyInAnyOrder("officer", "shareholder", "beneficial_owner");
        CompanyView.RoleView share = view.roles().stream().filter(r -> r.kind().equals("shareholder")).findFirst().orElseThrow();
        assertThat(share.shares()).contains("100");
        assertThat(share.holder().kind()).isEqualTo("joint_owners");
        assertThat(share.holder().owners()).extracting(CompanyView.PartyView::kind).containsExactlyInAnyOrder("natural_person", "latvian_entity");
        CompanyView.RoleView owner = view.roles().stream().filter(r -> r.kind().equals("beneficial_owner")).findFirst().orElseThrow();
        assertThat(owner.nationalities()).containsExactly("LV", "US");
        assertThat(owner.holder().personKey()).contains("abc");
    }

    @Test
    void roleOrderDoesNotDependOnSetOrder() {
        Role a = new Role.Officer(REG, PERSON, Optional.of("BOARD_MEMBER"), Optional.empty(), Optional.empty());
        Role b = new Role.Officer(REG, ENTITY, Optional.of("CHAIR_OF_BOARD"), Optional.empty(), Optional.empty());

        assertThat(Views.company(company(), new LinkedHashSet<>(List.of(a, b)), D))
                .isEqualTo(Views.company(company(), new LinkedHashSet<>(List.of(b, a)), D));
    }
}
