package io.github.cashewcodes9.lvregister.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * A change seen between two consecutive captures (PLAN.md section 8). The event id is derived
 * in `diff` from the registration number, type, capture date and payload.
 */
public sealed interface CompanyEvent {

    RegNumber regNumber();

    ObservedBetween observed();

    record Registered(RegNumber regNumber, ObservedBetween observed, Optional<LocalDate> registeredOn)
            implements CompanyEvent {
        public Registered {
            Checks.notNull(regNumber, "regNumber");
            Checks.notNull(observed, "observed");
            Checks.notNull(registeredOn, "registeredOn");
        }
    }

    record NameChanged(RegNumber regNumber, ObservedBetween observed, String oldName, String newName)
            implements CompanyEvent {
        public NameChanged {
            Checks.notNull(regNumber, "regNumber");
            Checks.notNull(observed, "observed");
            Checks.notBlank(oldName, "oldName");
            Checks.notBlank(newName, "newName");
            requireDifferent(oldName, newName);
        }
    }

    record AddressChanged(
            RegNumber regNumber, ObservedBetween observed, Optional<Address> oldAddress, Optional<Address> newAddress)
            implements CompanyEvent {
        public AddressChanged {
            Checks.notNull(regNumber, "regNumber");
            Checks.notNull(observed, "observed");
            Checks.notNull(oldAddress, "oldAddress");
            Checks.notNull(newAddress, "newAddress");
            requireDifferent(oldAddress, newAddress);
        }
    }

    record LegalFormChanged(RegNumber regNumber, ObservedBetween observed, LegalForm oldForm, LegalForm newForm)
            implements CompanyEvent {
        public LegalFormChanged {
            Checks.notNull(regNumber, "regNumber");
            Checks.notNull(observed, "observed");
            Checks.notNull(oldForm, "oldForm");
            Checks.notNull(newForm, "newForm");
            requireDifferent(oldForm, newForm);
        }
    }

    record ManagementChanged(
            RegNumber regNumber, ObservedBetween observed, Set<Role.Officer> added, Set<Role.Officer> removed)
            implements CompanyEvent {
        public ManagementChanged {
            Checks.notNull(regNumber, "regNumber");
            Checks.notNull(observed, "observed");
            added = Set.copyOf(Checks.notNull(added, "added"));
            removed = Set.copyOf(Checks.notNull(removed, "removed"));
            requireSomeChange(added, removed);
        }
    }

    /** Members, stockholders or beneficial owners changed. */
    record OwnershipChanged(RegNumber regNumber, ObservedBetween observed, Set<Role> added, Set<Role> removed)
            implements CompanyEvent {
        public OwnershipChanged {
            Checks.notNull(regNumber, "regNumber");
            Checks.notNull(observed, "observed");
            added = Set.copyOf(Checks.notNull(added, "added"));
            removed = Set.copyOf(Checks.notNull(removed, "removed"));
            requireSomeChange(added, removed);
            if (added.stream().anyMatch(Role.Officer.class::isInstance)
                    || removed.stream().anyMatch(Role.Officer.class::isInstance)) {
                throw new IllegalArgumentException("officers belong in ManagementChanged");
            }
        }
    }

    record StatusChanged(RegNumber regNumber, ObservedBetween observed, CompanyStatus oldStatus, CompanyStatus newStatus)
            implements CompanyEvent {
        public StatusChanged {
            Checks.notNull(regNumber, "regNumber");
            Checks.notNull(observed, "observed");
            Checks.notNull(oldStatus, "oldStatus");
            Checks.notNull(newStatus, "newStatus");
            requireDifferent(oldStatus, newStatus);
        }
    }

    /**
     * Termination date appeared. The roles of a terminated company vanish from the role files in
     * the same capture; no role events are emitted for it (source assessment 4.2).
     */
    record Terminated(RegNumber regNumber, ObservedBetween observed, Optional<LocalDate> terminatedOn)
            implements CompanyEvent {
        public Terminated {
            Checks.notNull(regNumber, "regNumber");
            Checks.notNull(observed, "observed");
            Checks.notNull(terminatedOn, "terminatedOn");
        }
    }

    /** In the previous capture, not in this one. Unusual: UR keeps closed entities. Flag for review. */
    record MissingFromSnapshot(RegNumber regNumber, ObservedBetween observed) implements CompanyEvent {
        public MissingFromSnapshot {
            Checks.notNull(regNumber, "regNumber");
            Checks.notNull(observed, "observed");
        }
    }

    private static void requireDifferent(Object oldValue, Object newValue) {
        if (oldValue.equals(newValue)) {
            throw new IllegalArgumentException("old and new value are equal");
        }
    }

    private static void requireSomeChange(Set<?> added, Set<?> removed) {
        if (added.isEmpty() && removed.isEmpty()) {
            throw new IllegalArgumentException("added and removed must not both be empty");
        }
    }
}
