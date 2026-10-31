package io.github.cashewcodes9.lvregister.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** A role of a party in one company. Role files hold the current state only, no end dates. */
public sealed interface Role {

    RegNumber company();

    Party holder();

    /**
     * Officer from `officers.csv`.
     *
     * @param position      UR position code (e.g. {@code BOARD_MEMBER}); missing for 29 % of rows
     * @param governingBody UR governing body code (e.g. {@code EXECUTIVE_BOARD})
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
            if (position.isEmpty() && governingBody.isEmpty()) {
                throw new IllegalArgumentException("officer needs a position or a governing body");
            }
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
            shares = shares.stripTrailingZeros();
        }
    }

    /** Beneficial owner. Always a natural person. Nationality and residence are ISO 3166 alpha-2. */
    record BeneficialOwner(
            RegNumber company,
            Party.NaturalPerson holder,
            Optional<String> nationality,
            Optional<String> residence,
            Optional<LocalDate> since) implements Role {
        public BeneficialOwner {
            Checks.notNull(company, "company");
            Checks.notNull(holder, "holder");
            Checks.notNull(nationality, "nationality");
            Checks.notNull(residence, "residence");
            Checks.notNull(since, "since");
        }
    }
}
