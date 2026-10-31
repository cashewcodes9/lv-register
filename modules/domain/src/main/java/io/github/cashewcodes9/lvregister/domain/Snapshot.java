package io.github.cashewcodes9.lvregister.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** All companies and roles of one capture, keyed by registration number. */
public record Snapshot(LocalDate date, Map<RegNumber, Company> companies, Map<RegNumber, Set<Role>> roles) {

    public Snapshot {
        Checks.notNull(date, "date");
        Checks.notNull(companies, "companies");
        Checks.notNull(roles, "roles");
        companies = Map.copyOf(companies);
        roles = roles.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
        companies.forEach((reg, company) -> {
            if (!reg.equals(company.regNumber())) {
                throw new IllegalArgumentException("company keyed under the wrong registration number: " + reg);
            }
        });
        roles.forEach((reg, set) -> set.forEach(role -> {
            if (!reg.equals(role.company())) {
                throw new IllegalArgumentException("role keyed under the wrong registration number: " + reg);
            }
        }));
    }

    public Set<Role> rolesOf(RegNumber regNumber) {
        return roles.getOrDefault(regNumber, Set.of());
    }
}
