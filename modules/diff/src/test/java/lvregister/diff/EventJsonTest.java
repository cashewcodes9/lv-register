package lvregister.diff;

import static lvregister.diff.Samples.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lvregister.domain.CompanyEvent;
import lvregister.domain.CompanyStatus;
import lvregister.domain.ObservedBetween;
import lvregister.domain.Party;
import lvregister.domain.RegNumber;
import lvregister.domain.Role;
import org.junit.jupiter.api.Test;

class EventJsonTest {

    private static final ObservedBetween OBSERVED = new ObservedBetween(D1, D2);

    @Test
    void lineHasSortedKeysAndPlainValues() {
        StoredEvent stored = new StoredEvent("a".repeat(64),
                new CompanyEvent.OwnershipChanged(REG, OBSERVED, Set.of(member(PERSON, "100.00")), Set.of()));

        assertThat(EventJson.line(stored)).isEqualTo("{\"current\":\"2026-11-09\",\"id\":\"" + "a".repeat(64) + "\","
                + "\"payload\":{\"added\":[{\"holder\":{\"key\":\"kkkkkkkk\",\"kind\":\"natural_person\",\"name\":\"Test Person\"},"
                + "\"kind\":\"shareholder\",\"nominalValue\":{\"amount\":\"1\",\"currency\":\"EUR\"},\"register\":\"MEMBERS\","
                + "\"shares\":\"100\"}],\"removed\":[]},"
                + "\"previous\":\"2026-11-02\",\"regNumber\":\"40003000001\",\"type\":\"OWNERSHIP_CHANGED\"}");
    }

    @Test
    void setOrderDoesNotChangeTheJsonOrTheId() {
        Role.Officer a = officer(PERSON, "BOARD_MEMBER");
        Role.Officer b = officer(OTHER_PERSON, "CHAIR_OF_BOARD");
        Set<Role.Officer> ab = new LinkedHashSet<>(List.of(a, b));
        Set<Role.Officer> ba = new LinkedHashSet<>(List.of(b, a));
        var e1 = new CompanyEvent.ManagementChanged(REG, OBSERVED, ab, Set.of());
        var e2 = new CompanyEvent.ManagementChanged(REG, OBSERVED, ba, Set.of());

        assertThat(EventJson.write(EventJson.payload(e1))).isEqualTo(EventJson.write(EventJson.payload(e2)));
        assertThat(EventIds.of(e1)).isEqualTo(EventIds.of(e2));
    }

    @Test
    void jointOwnersAreSortedToo() {
        Party joint1 = new Party.JointOwners(new LinkedHashSet<>(List.of(PERSON, OTHER_PERSON)));
        Party joint2 = new Party.JointOwners(new LinkedHashSet<>(List.of(OTHER_PERSON, PERSON)));

        assertThat(EventJson.write(EventJson.party(joint1))).isEqualTo(EventJson.write(EventJson.party(joint2)));
    }

    @Test
    void absentValuesAreLeftOut() {
        assertThat(EventJson.write(EventJson.payload(new CompanyEvent.Terminated(REG, OBSERVED, Optional.empty())))).isEqualTo("{}");
        assertThat(EventJson.write(EventJson.party(OTHER_PERSON))).doesNotContain("key");
    }

    @Test
    void idDependsOnCaptureTypeAndPayload() {
        var status = new CompanyEvent.StatusChanged(REG, OBSERVED, CompanyStatus.ACTIVE, CompanyStatus.INSOLVENT);
        var later = new CompanyEvent.StatusChanged(REG, new ObservedBetween(D1, D2.plusDays(7)), CompanyStatus.ACTIVE, CompanyStatus.INSOLVENT);
        var other = new CompanyEvent.StatusChanged(new RegNumber("40003000002"), OBSERVED, CompanyStatus.ACTIVE, CompanyStatus.INSOLVENT);

        assertThat(EventIds.of(status)).matches("[0-9a-f]{64}").isEqualTo(EventIds.of(status))
                .isNotEqualTo(EventIds.of(later)).isNotEqualTo(EventIds.of(other));
    }

    @Test
    void storeSortsByIdAndRejectsCollisions() {
        var a = new CompanyEvent.MissingFromSnapshot(REG, OBSERVED);
        var b = new CompanyEvent.MissingFromSnapshot(new RegNumber("40003000002"), OBSERVED);

        assertThat(EventIds.store(List.of(a, b))).extracting(StoredEvent::id).isSorted();
        assertThatIllegalStateException().isThrownBy(() -> EventIds.store(List.of(a, a)));
    }
}
