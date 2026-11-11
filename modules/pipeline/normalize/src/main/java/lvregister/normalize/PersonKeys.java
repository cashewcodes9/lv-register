package lvregister.normalize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import lvregister.domain.PersonKey;

/**
 * Best-effort person key (source assessment 4.3), conservative: normalized upper-case name plus
 * the masked personal code ({@code DDMMYY-*****}) when present, else plus the birth date, else
 * no key. The key is the sha256 of that text, so it carries no readable personal data.
 * Two people with the same name and the same six digits would share a key; this is rare, and
 * documented.
 */
public final class PersonKeys {

    private PersonKeys() {}

    public static Optional<PersonKey> of(String name, Optional<String> maskedCode, Optional<String> birthDate) {
        String normalizedName = Text.clean(name).orElseThrow().toUpperCase(Locale.ROOT);
        Optional<String> qualifier = maskedCode.flatMap(Text::clean).map(code -> "code:" + code)
                .or(() -> birthDate.flatMap(Text::clean).map(date -> "birth:" + date));
        return qualifier.map(q -> new PersonKey(sha256(normalizedName + "|" + q)));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
