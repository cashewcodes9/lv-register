package lvregister.normalize;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Text normalization before anything is compared: NFC, whitespace collapsed, trimmed. */
public final class Text {

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");

    private Text() {}

    /** Normalized text, or empty when the value is missing or blank. */
    public static Optional<String> clean(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = WHITESPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFC)).replaceAll(" ").strip();
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    /** Normalized text in upper case, for codes such as {@code SIA} or {@code BOARD_MEMBER}. */
    public static Optional<String> code(String value) {
        return clean(value).map(s -> s.toUpperCase(Locale.ROOT));
    }
}
