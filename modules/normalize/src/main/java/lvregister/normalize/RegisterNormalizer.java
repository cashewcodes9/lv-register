package lvregister.normalize;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lvregister.domain.Address;
import lvregister.domain.Cleaned;
import lvregister.domain.Company;
import lvregister.domain.CompanyStatus;
import lvregister.domain.LegalForm;
import lvregister.domain.Money;
import lvregister.domain.RegNumber;

/**
 * {@code register.csv} to {@link Company}, with status from the open proceedings and capital
 * from the equity file merged in.
 */
public final class RegisterNormalizer {

    public static final List<String> COLUMNS = List.of(
            "regcode", "name", "type", "registered", "terminated", "closed", "address", "index", "addressid", "atvk");

    public static final String INVALID_REGCODE = "register.invalid_regcode";
    public static final String MISSING_NAME = "register.missing_name";
    public static final String INVALID_LEGAL_FORM = "register.invalid_legal_form";
    public static final String TERMINATED_BEFORE_REGISTERED = "register.terminated_before_registered";
    public static final String DUPLICATE_REGCODE = "register.duplicate_regcode";
    public static final String INVALID_DATE = "register.invalid_date";
    public static final String INVALID_POSTAL_CODE = "register.invalid_postal_code";
    public static final String INVALID_ADDRESS_ID = "register.invalid_address_id";

    private RegisterNormalizer() {}

    public static Cleaned<Company> normalize(
            Stream<Row> rows, Map<RegNumber, OpenProceedings> open, Map<RegNumber, Money> capital) {
        Cleaned<Company> companies = Cleaning.collect(rows.map(row -> company(row, open, capital)));
        return Cleaning.dropDuplicates(companies, Company::regNumber, DUPLICATE_REGCODE);
    }

    static Outcome<Company> company(Row row, Map<RegNumber, OpenProceedings> open, Map<RegNumber, Money> capital) {
        Optional<RegNumber> reg = row.regNumber("regcode");
        if (reg.isEmpty()) {
            return Outcome.dropped(INVALID_REGCODE);
        }
        Optional<String> name = row.text("name");
        if (name.isEmpty()) {
            return Outcome.dropped(MISSING_NAME);
        }
        Optional<LegalForm> form = row.code("type").flatMap(code -> LegalForms.of(code, row));
        if (form.isEmpty()) {
            return Outcome.dropped(INVALID_LEGAL_FORM);
        }
        Optional<LocalDate> registered = row.date("registered", INVALID_DATE);
        Optional<LocalDate> terminated = row.date("terminated", INVALID_DATE);
        if (registered.isPresent() && terminated.isPresent() && terminated.get().isBefore(registered.get())) {
            return Outcome.dropped(TERMINATED_BEFORE_REGISTERED);
        }
        CompanyStatus status = Statuses.of(row.raw("closed"), terminated, open.getOrDefault(reg.get(), OpenProceedings.NONE), row);
        Company company = new Company(reg.get(), name.get(), form.get(), status, address(row), registered, terminated,
                Optional.ofNullable(capital.get(reg.get())));
        return Outcome.kept(company, row);
    }

    static Optional<Address> address(Row row) {
        Optional<String> line = row.text("address");
        if (line.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> postalCode = row.text("index");
        if (postalCode.isPresent() && !postalCode.get().matches("\\d{4}")) {
            row.flag(INVALID_POSTAL_CODE);
            postalCode = Optional.empty();
        }
        Optional<Long> vzdId = row.text("addressid").flatMap(id -> {
            if (!id.matches("\\d{1,18}")) {
                row.flag(INVALID_ADDRESS_ID);
                return Optional.empty();
            }
            long value = Long.parseLong(id);
            return value > 0 ? Optional.of(value) : Optional.empty();
        });
        return Optional.of(new Address(line.get(), postalCode, vzdId, row.text("atvk")));
    }
}
