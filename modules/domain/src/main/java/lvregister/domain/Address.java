package lvregister.domain;

import java.util.Optional;

/**
 * Registered address.
 *
 * @param line       normalized one-line address as published
 * @param postalCode postal code digits ({@code register.index}), without the {@code LV-} prefix
 * @param vzdId      State Address Register code ({@code register.addressid}); empty when UR has 0
 * @param atvk       administrative territory code
 */
public record Address(String line, Optional<String> postalCode, Optional<Long> vzdId, Optional<String> atvk) {

    public Address {
        Checks.notBlank(line, "line");
        Checks.notNull(postalCode, "postalCode");
        Checks.notNull(vzdId, "vzdId");
        Checks.notNull(atvk, "atvk");
        vzdId.ifPresent(id -> {
            if (id <= 0) {
                throw new IllegalArgumentException("vzdId must be positive");
            }
        });
    }
}
