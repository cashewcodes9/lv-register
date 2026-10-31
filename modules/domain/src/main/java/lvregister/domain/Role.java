package lvregister.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/** A role of a party in one company. Role files hold the current state only, no end dates. */
public sealed interface Role {

    RegNumber company();

    Party holder();

    /**
     * Officer from `officers.csv`.
     *
     * @param position      UR position code (e.g. {@code BOARD_MEMBER}); missing for 29 % of rows
     * @param governingBody UR governing body code (e.g. {@code EXECUTIVE_BOARD}). Both can be
     *                      missing: UR lists about 12K representatives, mostly of sole
     *                      proprietorships (IK), with representation rights only
     */
    record Officer(
            RegNumber company,
            Party holder,
            Optional<String> position,
            Optional<String> governingBody,
            Optional<LocalDate> since) implements Role {
        public Officer {
            Checks.notNull(company, "company");
            Checks.notNull(holder, "holder");
            Checks.notNull(position, "position");
            Checks.notNull(governingBody, "governingBody");
            Checks.notNull(since, "since");
        }
    }

    /** Which file a shareholding comes from. */
    enum ShareRegister { MEMBERS, STOCKHOLDERS }

    /** SIA member or AS stockholder. */
    record Shareholder(
            RegNumber company,
            Party holder,
            ShareRegister register,
            BigDecimal shares,
            Optional<Money> nominalValue,
            Optional<LocalDate> since) implements Role {
        public Shareholder {
            Checks.notNull(company, "company");
            Checks.notNull(holder, "holder");
            Checks.notNull(register, "register");
            Checks.notNull(shares, "shares");
            Checks.notNull(nominalValue, "nominalValue");
            Checks.notNull(since, "since");
            if (shares.signum() < 0) {
                throw new IllegalArgumentException("shares must not be negative");
            }
            shares = Decimals.canonical(shares);
        }
    }

    /**
     * Beneficial owner. Always a natural person. Nationalities and residence are ISO 3166
     * alpha-2 codes; a person can have several nationalities (UR lists them as {@code LV;US}).
     */
    record BeneficialOwner(
            RegNumber company,
            Party.NaturalPerson holder,
            Set<String> nationalities,
            Optional<String> residence,
            Optional<LocalDate> since) implements Role {
        public BeneficialOwner {
            Checks.notNull(company, "company");
            Checks.notNull(holder, "holder");
            nationalities = Set.copyOf(Checks.notNull(nationalities, "nationalities"));
            Checks.notNull(residence, "residence");
            Checks.notNull(since, "since");
        }
    }
}
