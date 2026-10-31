package lvregister.serving;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lvregister.domain.Address;
import lvregister.domain.Company;
import lvregister.domain.Money;
import lvregister.domain.Party;
import lvregister.domain.PersonKey;
import lvregister.domain.RegNumber;
import lvregister.domain.Role;

/** Maps domain records to serving views. Pure; roles in a stable order, so documents are stable. */
public final class Views {

    private Views() {}

    public static CompanyView company(Company c, Set<Role> roles, LocalDate asOf) {
        List<CompanyView.RoleView> roleViews = roles.stream().map(Views::role)
                .sorted(Comparator.comparing(CompanyView.RoleView::toString)).toList();
        return new CompanyView(c.regNumber().value(), c.name(), c.legalForm().code(), c.legalForm().family().name(),
                c.status().name(), c.address().map(Views::address), c.registered(), c.terminated(),
                c.capital().map(Views::money), roleViews, asOf);
    }

    static CompanyView.RoleView role(Role role) {
        return switch (role) {
            case Role.Officer o -> new CompanyView.RoleView("officer", party(o.holder()), o.position(), o.governingBody(),
                    Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), o.since());
            case Role.Shareholder s -> new CompanyView.RoleView("shareholder", party(s.holder()), Optional.empty(),
                    Optional.empty(), Optional.of(s.register().name()), Optional.of(s.shares().toPlainString()),
                    s.nominalValue().map(Views::money), List.of(), Optional.empty(), s.since());
            case Role.BeneficialOwner b -> new CompanyView.RoleView("beneficial_owner", party(b.holder()), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    b.nationalities().stream().sorted().toList(), b.residence(), b.since());
        };
    }

    static CompanyView.PartyView party(Party party) {
        return switch (party) {
            case Party.NaturalPerson(String name, Optional<PersonKey> key) ->
                    new CompanyView.PartyView("natural_person", name, Optional.empty(), key.map(PersonKey::value), List.of());
            case Party.LatvianEntity(RegNumber reg, String name) ->
                    new CompanyView.PartyView("latvian_entity", name, Optional.of(reg.value()), Optional.empty(), List.of());
            case Party.ForeignEntity(String name) ->
                    new CompanyView.PartyView("foreign_entity", name, Optional.empty(), Optional.empty(), List.of());
            case Party.JointOwners(Set<Party> owners) -> {
                List<CompanyView.PartyView> views = owners.stream().map(Views::party)
                        .sorted(Comparator.comparing(CompanyView.PartyView::toString)).toList();
                yield new CompanyView.PartyView("joint_owners", "joint owners", Optional.empty(), Optional.empty(), views);
            }
        };
    }

    private static CompanyView.AddressView address(Address a) {
        return new CompanyView.AddressView(a.line(), a.postalCode(), a.vzdId(), a.atvk());
    }

    private static CompanyView.MoneyView money(Money m) {
        return new CompanyView.MoneyView(m.amount().toPlainString(), m.currency());
    }
}
