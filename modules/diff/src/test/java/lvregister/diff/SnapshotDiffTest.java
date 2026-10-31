package lvregister.diff;

import static lvregister.diff.Samples.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lvregister.domain.Company;
import lvregister.domain.CompanyEvent;
import lvregister.domain.CompanyStatus;
import lvregister.domain.ObservedBetween;
import lvregister.domain.RegNumber;
import lvregister.domain.Role;
import lvregister.domain.Snapshot;
import org.junit.jupiter.api.Test;

class SnapshotDiffTest {

    private static final ObservedBetween OBSERVED = new ObservedBetween(D1, D2);

    private static Snapshot snapshot(LocalDate date, Company company, Set<Role> roles) {
        return new Snapshot(date, Map.of(company.regNumber(), company), roles.isEmpty() ? Map.of() : Map.of(company.regNumber(), roles));
    }

    private static List<CompanyEvent> diff(Company a, Set<Role> rolesA, Company b, Set<Role> rolesB) {
        return SnapshotDiff.diff(snapshot(D1, a, rolesA), snapshot(D2, b, rolesB));
    }

    @Test
    void identicalSnapshotsGiveNoEvents() {
        assertThat(diff(active("SIA \"A\""), Set.of(officer(PERSON, "BOARD_MEMBER")),
                active("SIA \"A\""), Set.of(officer(PERSON, "BOARD_MEMBER")))).isEmpty();
    }

    @Test
    void newAndMissingCompanies() {
        RegNumber other = new RegNumber("40003000002");
        Company b = company(other, "SIA \"B\"", SIA, CompanyStatus.ACTIVE, Optional.empty());

        List<CompanyEvent> events = SnapshotDiff.diff(snapshot(D1, active("SIA \"A\""), Set.of()), snapshot(D2, b, Set.of()));

        assertThat(events).containsExactly(
                new CompanyEvent.MissingFromSnapshot(REG, OBSERVED),
                new CompanyEvent.Registered(other, OBSERVED, Optional.of(LocalDate.of(2010, 1, 2))));
    }

    @Test
    void oneEventPerChangedField() {
        Company before = active("SIA \"A\"");
        Company after = new Company(REG, "SIA \"A2\"", AS, CompanyStatus.IN_LIQUIDATION, Optional.of(address("Rīga, Jauna iela 2")),
                before.registered(), Optional.empty(), before.capital());

        assertThat(diff(before, Set.of(), after, Set.of())).containsExactly(
                new CompanyEvent.NameChanged(REG, OBSERVED, "SIA \"A\"", "SIA \"A2\""),
                new CompanyEvent.AddressChanged(REG, OBSERVED, before.address(), after.address()),
                new CompanyEvent.LegalFormChanged(REG, OBSERVED, SIA, AS),
                new CompanyEvent.StatusChanged(REG, OBSERVED, CompanyStatus.ACTIVE, CompanyStatus.IN_LIQUIDATION));
    }

    @Test
    void roleChangesSplitIntoManagementAndOwnership() {
        Role.Officer stays = officer(PERSON, "CHAIR_OF_BOARD");
        Role.Officer leaves = officer(OTHER_PERSON, "BOARD_MEMBER");
        Role.Officer joins = officer(OTHER_PERSON, "CHAIR_OF_BOARD");
        Role.Shareholder sharesBefore = member(PERSON, "100");
        Role.Shareholder sharesAfter = member(PERSON, "60");

        List<CompanyEvent> events = diff(active("A"), Set.of(stays, leaves, sharesBefore), active("A"), Set.of(stays, joins, sharesAfter));

        assertThat(events).containsExactly(
                new CompanyEvent.ManagementChanged(REG, OBSERVED, Set.of(joins), Set.of(leaves)),
                new CompanyEvent.OwnershipChanged(REG, OBSERVED, Set.of(sharesAfter), Set.of(sharesBefore)));
    }

    @Test
    void terminationEmitsOnlyTerminated() {
        Company closed = company(REG, "SIA \"A2\"", SIA, CompanyStatus.TERMINATED, Optional.of(D2.minusDays(3)));

        assertThat(diff(active("SIA \"A\""), Set.of(officer(PERSON, "BOARD_MEMBER"), member(PERSON, "100")), closed, Set.of()))
                .containsExactly(new CompanyEvent.Terminated(REG, OBSERVED, Optional.of(D2.minusDays(3))));
    }

    @Test
    void reorganizationWithoutDateIsATerminationToo() {
        Company reorganized = company(REG, "SIA \"A\"", SIA, CompanyStatus.REORGANIZED, Optional.empty());

        assertThat(diff(active("SIA \"A\""), Set.of(), reorganized, Set.of()))
                .containsExactly(new CompanyEvent.Terminated(REG, OBSERVED, Optional.empty()));
    }

    @Test
    void changesOfAnAlreadyClosedCompanyAreReported() {
        Company closed = company(REG, "SIA \"A\"", SIA, CompanyStatus.TERMINATED, Optional.of(D1.minusDays(30)));
        Company renamed = company(REG, "SIA \"B\"", SIA, CompanyStatus.TERMINATED, Optional.of(D1.minusDays(30)));

        assertThat(diff(closed, Set.of(), renamed, Set.of()))
                .containsExactly(new CompanyEvent.NameChanged(REG, OBSERVED, "SIA \"A\"", "SIA \"B\""));
    }
}
