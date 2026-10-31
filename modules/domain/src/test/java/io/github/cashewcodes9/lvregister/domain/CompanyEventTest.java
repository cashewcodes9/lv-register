package io.github.cashewcodes9.lvregister.domain;

import static io.github.cashewcodes9.lvregister.domain.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.cashewcodes9.lvregister.domain.CompanyEvent.*;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompanyEventTest {

    @Test
    void changeEventsRejectEqualOldAndNewValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NameChanged(REG, OBSERVED, "A", "A"));
        assertThatIllegalArgumentException().isThrownBy(() -> new AddressChanged(REG, OBSERVED, Optional.empty(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalFormChanged(REG, OBSERVED, SIA, SIA));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new StatusChanged(REG, OBSERVED, CompanyStatus.ACTIVE, CompanyStatus.ACTIVE));
    }

    @Test
    void roleEventsNeedAChangeAndKeepOfficersSeparate() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementChanged(REG, OBSERVED, Set.of(), Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OwnershipChanged(REG, OBSERVED, Set.of(officer(REG)), Set.of()));
        var share = new Role.Shareholder(REG, PERSON, Role.ShareRegister.MEMBERS, BigDecimal.TEN, Optional.empty(), Optional.empty());
        assertThat(new OwnershipChanged(REG, OBSERVED, Set.of(share), Set.of()).added()).containsExactly(share);
    }

    @Test
    void sealedHierarchyIsExhaustiveInASwitch() {
        CompanyEvent event = new Terminated(REG, OBSERVED, Optional.of(D2));
        String type = switch (event) {
            case Registered r -> "registered";
            case NameChanged n -> "name";
            case AddressChanged a -> "address";
            case LegalFormChanged l -> "legal-form";
            case ManagementChanged m -> "management";
            case OwnershipChanged o -> "ownership";
            case StatusChanged s -> "status";
            case Terminated(var reg, var observed, var on) -> "terminated " + on.orElseThrow();
            case MissingFromSnapshot m -> "missing";
        };
        assertThat(type).isEqualTo("terminated 2026-11-09");
    }

    @Test
    void registeredAndMissingCarryRegNumberAndObservedDates() {
        assertThat(new Registered(REG, OBSERVED, Optional.empty()).observed().gapDays()).isEqualTo(7);
        assertThat(new MissingFromSnapshot(REG, OBSERVED).regNumber()).isEqualTo(REG);
    }
}
