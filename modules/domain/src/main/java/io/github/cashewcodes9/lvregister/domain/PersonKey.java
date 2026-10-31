package io.github.cashewcodes9.lvregister.domain;

/**
 * Best-effort key for a natural person, built conservatively in `normalize` from the normalized
 * name plus the masked personal code or the birth date (source assessment 4.3). Opaque here.
 * Never log it.
 */
public record PersonKey(String value) {

    public PersonKey {
        Checks.notBlank(value, "value");
    }

    @Override
    public String toString() {
        return "PersonKey[redacted]";
    }
}
