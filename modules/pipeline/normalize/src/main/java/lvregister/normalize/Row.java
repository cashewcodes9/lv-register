package lvregister.normalize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lvregister.domain.RegNumber;

/**
 * One source row: column name to text, missing columns meaning NULL. Collects the flags raised
 * while its values are read, for the row's outcome.
 */
public final class Row {

    private final Map<String, String> values;
    private final List<String> flags = new ArrayList<>();

    public Row(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static Row of(Map<String, String> values) {
        return new Row(values);
    }

    public Optional<String> text(String column) {
        return Text.clean(values.get(column));
    }

    public Optional<String> code(String column) {
        return Text.code(values.get(column));
    }

    /** The raw value without normalization (e.g. {@code register.closed}, where a space means active). */
    public Optional<String> raw(String column) {
        return Optional.ofNullable(values.get(column));
    }

    /** A registration number, or empty when missing or malformed. */
    public Optional<RegNumber> regNumber(String column) {
        return text(column).filter(s -> s.matches("\\d{9}|\\d{11}")).map(RegNumber::new);
    }

    /** An ISO date; a malformed value is flagged with {@code rule} and read as missing. */
    public Optional<LocalDate> date(String column, String rule) {
        Optional<String> text = text(column);
        try {
            return text.map(LocalDate::parse);
        } catch (DateTimeParseException e) {
            flag(rule);
            return Optional.empty();
        }
    }

    /** A decimal with {@code .} as separator, or empty when missing or malformed. */
    public Optional<BigDecimal> decimal(String column) {
        return text(column).flatMap(s -> {
            try {
                return Optional.of(new BigDecimal(s));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    public void flag(String rule) {
        flags.add(rule);
    }

    List<String> flags() {
        return List.copyOf(flags);
    }
}
