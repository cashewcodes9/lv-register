package lvregister.diff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lvregister.domain.Company;
import lvregister.domain.CompanyEvent;
import lvregister.domain.ObservedBetween;
import lvregister.domain.RegNumber;
import lvregister.domain.Role;
import lvregister.domain.Snapshot;

/**
 * Compares the previous capture A with the current capture B, keyed by registration number
 * (PLAN.md section 8). Pure: no I/O, the same inputs always give the same events.
 *
 * <ul>
 *   <li>in B, not in A: {@code Registered}; in A, not in B: {@code MissingFromSnapshot}</li>
 *   <li>a company that becomes closed (termination date appears, or status becomes TERMINATED
 *       or REORGANIZED): only {@code Terminated}. Its roles vanish from the role files in the same
 *       capture, so no other events are emitted for it (source assessment 4.2).</li>
 *   <li>otherwise one event per changed field: name, address, legal form, status, officer set
 *       ({@code ManagementChanged}), member/stockholder/BO set ({@code OwnershipChanged})</li>
 * </ul>
 * Values are already normalized, so formatting noise cannot create events.
 */
public final class SnapshotDiff {

    private SnapshotDiff() {}

    public static List<CompanyEvent> diff(Snapshot a, Snapshot b) {
        ObservedBetween observed = new ObservedBetween(a.date(), b.date());
        Set<RegNumber> all = new TreeSet<>(a.companies().keySet());
        all.addAll(b.companies().keySet());
        List<CompanyEvent> events = new ArrayList<>();
        for (RegNumber reg : all) {
            Company before = a.companies().get(reg);
            Company after = b.companies().get(reg);
            if (before == null) {
                events.add(new CompanyEvent.Registered(reg, observed, after.registered()));
            } else if (after == null) {
                events.add(new CompanyEvent.MissingFromSnapshot(reg, observed));
            } else {
                events.addAll(changes(observed, before, after, a.rolesOf(reg), b.rolesOf(reg)));
            }
        }
        return events;
    }

    static List<CompanyEvent> changes(ObservedBetween observed, Company before, Company after,
            Set<Role> rolesBefore, Set<Role> rolesAfter) {
        RegNumber reg = after.regNumber();
        if (becameClosed(before, after)) {
            return List.of(new CompanyEvent.Terminated(reg, observed, after.terminated()));
        }
        List<CompanyEvent> events = new ArrayList<>();
        if (!before.name().equals(after.name())) {
            events.add(new CompanyEvent.NameChanged(reg, observed, before.name(), after.name()));
        }
        if (!before.address().equals(after.address())) {
            events.add(new CompanyEvent.AddressChanged(reg, observed, before.address(), after.address()));
        }
        if (!before.legalForm().equals(after.legalForm())) {
            events.add(new CompanyEvent.LegalFormChanged(reg, observed, before.legalForm(), after.legalForm()));
        }
        if (before.status() != after.status()) {
            events.add(new CompanyEvent.StatusChanged(reg, observed, before.status(), after.status()));
        }
        Set<Role.Officer> officersBefore = officers(rolesBefore);
        Set<Role.Officer> officersAfter = officers(rolesAfter);
        if (!officersBefore.equals(officersAfter)) {
            events.add(new CompanyEvent.ManagementChanged(reg, observed,
                    minus(officersAfter, officersBefore), minus(officersBefore, officersAfter)));
        }
        Set<Role> ownersBefore = ownership(rolesBefore);
        Set<Role> ownersAfter = ownership(rolesAfter);
        if (!ownersBefore.equals(ownersAfter)) {
            events.add(new CompanyEvent.OwnershipChanged(reg, observed,
                    minus(ownersAfter, ownersBefore), minus(ownersBefore, ownersAfter)));
        }
        return events;
    }

    static boolean becameClosed(Company before, Company after) {
        boolean dateAppeared = before.terminated().isEmpty() && after.terminated().isPresent();
        return dateAppeared || (!before.status().isClosed() && after.status().isClosed());
    }

    private static Set<Role.Officer> officers(Set<Role> roles) {
        return roles.stream().filter(Role.Officer.class::isInstance).map(Role.Officer.class::cast).collect(Collectors.toSet());
    }

    private static Set<Role> ownership(Set<Role> roles) {
        return roles.stream().filter(r -> !(r instanceof Role.Officer)).collect(Collectors.toSet());
    }

    private static <T> Set<T> minus(Set<T> a, Set<T> b) {
        Set<T> result = new HashSet<>(a);
        result.removeAll(b);
        return result;
    }
}
