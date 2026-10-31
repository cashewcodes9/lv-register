package lvregister.domain;

import java.util.Optional;
import java.util.Set;

/** Who holds a role: a person, a Latvian entity, a foreign entity, or joint owners of a share. */
public sealed interface Party {

    /** A natural person. Without a key the person is linked to this one company only. */
    record NaturalPerson(String name, Optional<PersonKey> key) implements Party {
        public NaturalPerson {
            Checks.notBlank(name, "name");
            Checks.notNull(key, "key");
        }

        @Override
        public String toString() {
            return "NaturalPerson[redacted]";
        }
    }

    /** An entity in the Latvian register, identified by its registration number. */
    record LatvianEntity(RegNumber regNumber, String name) implements Party {
        public LatvianEntity {
            Checks.notNull(regNumber, "regNumber");
            Checks.notBlank(name, "name");
        }
    }

    /** A foreign entity, known by name only. */
    record ForeignEntity(String name) implements Party {
        public ForeignEntity {
            Checks.notBlank(name, "name");
        }
    }

    /** Several co-owners of one jointly held share ({@code *_joint_owners.csv}). */
    record JointOwners(Set<Party> owners) implements Party {
        public JointOwners {
            Checks.notNull(owners, "owners");
            owners = Set.copyOf(owners);
            if (owners.size() < 2) {
                throw new IllegalArgumentException("joint owners need at least two owners");
            }
            if (owners.stream().anyMatch(JointOwners.class::isInstance)) {
                throw new IllegalArgumentException("joint owners must not nest");
            }
        }
    }
}
