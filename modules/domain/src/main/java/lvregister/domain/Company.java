package lvregister.domain;

import java.time.LocalDate;
import java.util.Optional;

/**
 * One entity from `register.csv`, with status and capital merged in from the status and
 * equity files.
 */
public record Company(
        RegNumber regNumber,
        String name,
        LegalForm legalForm,
        CompanyStatus status,
        Optional<Address> address,
        Optional<LocalDate> registered,
        Optional<LocalDate> terminated,
        Optional<Money> capital) {

    public Company {
        Checks.notNull(regNumber, "regNumber");
        Checks.notBlank(name, "name");
        Checks.notNull(legalForm, "legalForm");
        Checks.notNull(status, "status");
        Checks.notNull(address, "address");
        Checks.notNull(registered, "registered");
        Checks.notNull(terminated, "terminated");
        Checks.notNull(capital, "capital");
        if (registered.isPresent() && terminated.isPresent() && terminated.get().isBefore(registered.get())) {
            throw new IllegalArgumentException("terminated before registered: " + regNumber);
        }
    }
}
