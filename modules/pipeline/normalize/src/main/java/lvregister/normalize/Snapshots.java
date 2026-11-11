package lvregister.normalize;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lvregister.domain.Company;
import lvregister.domain.RegNumber;
import lvregister.domain.Role;
import lvregister.domain.Snapshot;

/** Builds the {@link Snapshot} of one capture from the normalized files. */
public final class Snapshots {

    /**
     * The snapshot plus what assembly removed: roles of companies that are not in the register
     * (orphans; 0 in the 2026-10-31 capture) and roles that are exact duplicates of another role.
     */
    public record Assembly(Snapshot snapshot, long orphanRoles, long duplicateRoles) {}

    private Snapshots() {}

    public static Assembly assemble(LocalDate date, List<Company> companies, Collection<? extends List<? extends Role>> roleLists) {
        Map<RegNumber, Company> byReg = companies.stream()
                .collect(Collectors.toMap(Company::regNumber, c -> c, (a, b) -> {
                    throw new IllegalArgumentException("duplicate company " + a.regNumber() + ": drop duplicates first");
                }));
        Map<RegNumber, Set<Role>> roles = new HashMap<>();
        long orphans = 0;
        long duplicates = 0;
        for (List<? extends Role> list : roleLists) {
            for (Role role : list) {
                if (!byReg.containsKey(role.company())) {
                    orphans++;
                } else if (!roles.computeIfAbsent(role.company(), r -> new HashSet<>()).add(role)) {
                    duplicates++;
                }
            }
        }
        return new Assembly(new Snapshot(date, byReg, roles), orphans, duplicates);
    }
}
