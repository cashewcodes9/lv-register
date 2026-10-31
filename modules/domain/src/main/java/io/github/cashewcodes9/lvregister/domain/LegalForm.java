package io.github.cashewcodes9.lvregister.domain;

import java.util.regex.Pattern;

/**
 * Legal form: the UR code from `register.type` (e.g. {@code SIA}, upper case) and its family.
 * The code is kept so that a change of code within one family is still visible.
 */
public record LegalForm(String code, LegalFamily family) {

    private static final Pattern CODE = Pattern.compile("[A-Z]{2,4}");

    public LegalForm {
        Checks.notNull(code, "code");
        Checks.notNull(family, "family");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("legal form code must be 2-4 upper-case letters: " + code);
        }
    }
}
