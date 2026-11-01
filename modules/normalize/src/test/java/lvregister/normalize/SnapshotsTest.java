package lvregister.normalize;

import static lvregister.normalize.Rows.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lvregister.domain.Company;
import lvregister.domain.RegNumber;
import lvregister.domain.Role;
import org.junit.jupiter.api.Test;

class SnapshotsTest {

    @Test
    void attachesRolesAndCountsOrphansAndDuplicates() {
        List<Company> companies = RegisterNormalizer.normalize(Stream.of(
                row("regcode", "40003000001", "name", "SIA \"A\"", "type", "SIA", "closed", " ")), Map.of(), Map.of()).rows();
        Row officer = row("at_legal_entity_registration_number", "40003000001", "entity_type", "FOREIGN_ENTITY",
                "name", "X Ltd", "position", "MEMBER");
        Row orphan = row("at_legal_entity_registration_number", "40003000099", "entity_type", "FOREIGN_ENTITY",
                "name", "X Ltd", "position", "MEMBER");
        List<Role.Officer> officers = RoleNormalizers.officers(Stream.of(officer, officer, orphan)).rows();

        Snapshots.Assembly assembly = Snapshots.assemble(LocalDate.of(2026, 11, 2), companies, List.of(officers));

        assertThat(assembly.snapshot().companies()).containsOnlyKeys(new RegNumber("40003000001"));
        assertThat(assembly.snapshot().rolesOf(new RegNumber("40003000001"))).hasSize(1);
        assertThat(assembly.orphanRoles()).isEqualTo(1);
        assertThat(assembly.duplicateRoles()).isEqualTo(1);
    }
}
