package lvregister.diff;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LakeCapturesTest {

    private static final LocalDate A = LocalDate.of(2026, 11, 2);
    private static final LocalDate B = LocalDate.of(2026, 11, 9);
    private static final LocalDate C = LocalDate.of(2026, 11, 16);

    @Test
    void diffMayRunWhenThePreviousCaptureIsStillInTheLake() {
        assertThatCode(() -> LakeCaptures.requirePreviousInLake(List.of(A, B, C), List.of(A, B, C), C)).doesNotThrowAnyException();
        assertThatCode(() -> LakeCaptures.requirePreviousInLake(List.of(A), List.of(A), A)).doesNotThrowAnyException();
        assertThatCode(() -> LakeCaptures.requirePreviousInLake(List.of(B, C), List.of(A, B, C), C)).doesNotThrowAnyException();
    }

    @Test
    void diffRefusesWhenThePreviousCaptureWasPruned() {
        assertThatIllegalStateException()
                .isThrownBy(() -> LakeCaptures.requirePreviousInLake(List.of(B, C), List.of(A, B, C), B))
                .withMessageContaining("2026-11-02");
    }
}
