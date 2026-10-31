package lvregister.normalize;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lvregister.domain.Cleaned;
import lvregister.domain.Money;
import lvregister.domain.RegNumber;

/** The files that feed status and capital: liquidations, insolvency, equity, reorganizations. */
public final class StatusNormalizers {

    public static final List<String> LIQUIDATION_COLUMNS = List.of("legal_entity_registration_number", "liquidation_type", "date_from");
    public static final List<String> INSOLVENCY_COLUMNS = List.of(
            "debtor_registration_number", "proceeding_form", "proceeding_started_on", "proceeding_ended_on");
    public static final List<String> EQUITY_COLUMNS = List.of("legal_entity_registration_number", "equity_capital_type", "amount", "currency");
    public static final List<String> REORGANIZATION_COLUMNS = List.of(
            "reorganization_type", "source_entity_regcode", "final_entity_regcode", "registered");

    /** Capital types in order of preference: the registered amount, else the paid-up amount. */
    static final List<String> CAPITAL_PREFERENCE = List.of("REGISTERED_EQUITY", "PAID_UP_EQUITY");

    private StatusNormalizers() {}

    public static Cleaned<Liquidation> liquidations(Stream<Row> rows) {
        return Cleaning.collect(rows.map(row -> {
            Optional<RegNumber> reg = row.regNumber("legal_entity_registration_number");
            if (reg.isEmpty()) {
                return Outcome.<Liquidation>dropped("liquidations.invalid_regcode");
            }
            return Outcome.kept(new Liquidation(reg.get(), row.code("liquidation_type").orElse("UNKNOWN"),
                    row.date("date_from", "liquidations.invalid_date")), row);
        }));
    }

    public static Cleaned<Proceeding> proceedings(Stream<Row> rows) {
        return Cleaning.collect(rows.map(row -> {
            Optional<RegNumber> reg = row.regNumber("debtor_registration_number");
            if (reg.isEmpty()) {
                return Outcome.<Proceeding>dropped("insolvency.invalid_regcode");
            }
            Optional<String> form = row.code("proceeding_form");
            if (form.isEmpty()) {
                return Outcome.<Proceeding>dropped("insolvency.missing_form");
            }
            if (!Statuses.knownForm(form.get())) {
                row.flag(Statuses.UNMAPPED_FORM);
            }
            return Outcome.kept(new Proceeding(reg.get(), form.get(),
                    row.date("proceeding_started_on", "insolvency.invalid_date"),
                    row.date("proceeding_ended_on", "insolvency.invalid_date")), row);
        }));
    }

    public static Cleaned<EquityCapital> equityCapitals(Stream<Row> rows) {
        return Cleaning.collect(rows.map(row -> {
            Optional<RegNumber> reg = row.regNumber("legal_entity_registration_number");
            if (reg.isEmpty()) {
                return Outcome.<EquityCapital>dropped("equity.invalid_regcode");
            }
            Optional<String> type = row.code("equity_capital_type");
            if (type.isEmpty()) {
                return Outcome.<EquityCapital>dropped("equity.missing_type");
            }
            Optional<java.math.BigDecimal> amount = row.decimal("amount").filter(a -> a.signum() >= 0);
            if (amount.isEmpty()) {
                return Outcome.<EquityCapital>dropped("equity.invalid_amount");
            }
            Optional<String> currency = row.code("currency").filter(c -> c.matches("[A-Z]{3}"));
            if (currency.isEmpty()) {
                return Outcome.<EquityCapital>dropped("equity.missing_currency");
            }
            return Outcome.kept(new EquityCapital(reg.get(), type.get(), new Money(amount.get(), currency.get())), row);
        }));
    }

    public static Cleaned<Reorganization> reorganizations(Stream<Row> rows) {
        return Cleaning.collect(rows.map(row -> {
            Optional<RegNumber> source = row.regNumber("source_entity_regcode");
            Optional<RegNumber> target = row.regNumber("final_entity_regcode");
            if (source.isEmpty() || target.isEmpty()) {
                return Outcome.<Reorganization>dropped("reorganizations.invalid_regcode");
            }
            return Outcome.kept(new Reorganization(row.code("reorganization_type").orElse("UNKNOWN"), source.get(), target.get(),
                    row.date("registered", "reorganizations.invalid_date")), row);
        }));
    }

    /** Open liquidations and open proceedings per company. */
    public static Map<RegNumber, OpenProceedings> openProceedings(List<Liquidation> liquidations, List<Proceeding> proceedings) {
        Set<RegNumber> liquidating = liquidations.stream().map(Liquidation::regNumber).collect(Collectors.toSet());
        Map<RegNumber, Set<String>> forms = new HashMap<>();
        proceedings.stream().filter(Proceeding::isOpen)
                .forEach(p -> forms.computeIfAbsent(p.regNumber(), r -> new HashSet<>()).add(p.form()));
        Set<RegNumber> all = new HashSet<>(liquidating);
        all.addAll(forms.keySet());
        return all.stream().collect(Collectors.toUnmodifiableMap(r -> r,
                r -> new OpenProceedings(liquidating.contains(r), forms.getOrDefault(r, Set.of()))));
    }

    /** One capital per company: registered equity if listed, else paid-up equity; other types are ignored. */
    public static Map<RegNumber, Money> capital(List<EquityCapital> capitals) {
        return capitals.stream()
                .filter(c -> CAPITAL_PREFERENCE.contains(c.type()))
                .collect(Collectors.groupingBy(EquityCapital::regNumber, Collectors.collectingAndThen(
                        Collectors.minBy(Comparator.comparingInt(c -> CAPITAL_PREFERENCE.indexOf(c.type()))),
                        c -> c.orElseThrow().amount())));
    }
}
