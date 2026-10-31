package lvregister.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import lvregister.domain.PersonKey;
import org.junit.jupiter.api.Test;

class PersonKeysTest {

    @Test
    void sameNameAndCodeGiveTheSameKeyRegardlessOfCaseAndSpacing() {
        assertThat(PersonKeys.of("Test  Person", Optional.of("010101-*****"), Optional.empty()))
                .isEqualTo(PersonKeys.of("TEST PERSON", Optional.of("010101-*****"), Optional.empty()));
    }

    @Test
    void codeAndBirthDateAreDifferentQualifiers() {
        Optional<PersonKey> byCode = PersonKeys.of("A B", Optional.of("1980-01-01"), Optional.empty());
        Optional<PersonKey> byBirth = PersonKeys.of("A B", Optional.empty(), Optional.of("1980-01-01"));

        assertThat(byCode).isPresent().isNotEqualTo(byBirth);
    }

    @Test
    void codeWinsOverBirthDate() {
        assertThat(PersonKeys.of("A B", Optional.of("010101-*****"), Optional.of("1980-01-01")))
                .isEqualTo(PersonKeys.of("A B", Optional.of("010101-*****"), Optional.empty()));
    }

    @Test
    void noQualifierNoKey() {
        assertThat(PersonKeys.of("A B", Optional.empty(), Optional.empty())).isEmpty();
    }

    @Test
    void keyIsAHashWithoutTheName() {
        PersonKey key = PersonKeys.of("Test Person", Optional.of("010101-*****"), Optional.empty()).orElseThrow();

        assertThat(key.value()).matches("[0-9a-f]{64}").doesNotContainIgnoringCase("person");
        assertThat(key.toString()).isEqualTo("PersonKey[redacted]");
    }
}
