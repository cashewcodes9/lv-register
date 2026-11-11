package lvregister.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import lvregister.diff.DiffStep;
import lvregister.diff.LakeCaptures;
import lvregister.ingest.RawLayout;
import lvregister.ingest.SourceFile;
import lvregister.lake.LakeLayout;
import lvregister.storage.LocalDirectoryObjectStore;
import lvregister.storage.ObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PruneStepTest {

    private static final List<LocalDate> WEEKS = List.of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9),
            LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23));

    @TempDir
    Path tmp;

    private ObjectStore raw;
    private ObjectStore lake;

    @BeforeEach
    void setUp() throws Exception {
        raw = new LocalDirectoryObjectStore(tmp.resolve("raw"));
        lake = new LocalDirectoryObjectStore(tmp.resolve("lake"));
        for (LocalDate date : WEEKS) {
            capture(date);
        }
    }

    @Test
    void keepsTheLatestCapturesPerLayerAndEveryManifestEventAndReport() throws Exception {
        PruneStep.Result result = new PruneStep(raw, lake, 2, 3).run();

        assertThat(result.rawPruned()).containsExactly(WEEKS.get(0), WEEKS.get(1));
        assertThat(result.lakePruned()).containsExactly(WEEKS.get(0));
        assertThat(result.kept()).isEmpty();
        assertThat(result.bytesFreed()).isEqualTo(2L * SourceFile.values().length * 3 + SourceFile.values().length * 7L);
        assertThat(RawLayout.captures(raw)).isEqualTo(WEEKS);
        assertThat(raw.list(RawLayout.prefix(WEEKS.get(0)))).containsExactly(RawLayout.manifestKey(WEEKS.get(0)));
        assertThat(raw.exists(RawLayout.csvKey(WEEKS.get(2), SourceFile.REGISTER))).isTrue();
        assertThat(LakeCaptures.dates(lake)).isEqualTo(WEEKS.subList(1, 4));
        for (LocalDate date : WEEKS) {
            assertThat(lake.exists(DiffStep.eventsKey(date))).isTrue();
            assertThat(lake.exists(QualityReport.key(date))).isTrue();
        }
    }

    @Test
    void runningTwiceDeletesNothingMore() throws Exception {
        new PruneStep(raw, lake, 2, 3).run();
        PruneStep.Result again = new PruneStep(raw, lake, 2, 3).run();
        assertThat(again.rawPruned()).isEmpty();
        assertThat(again.lakePruned()).isEmpty();
        assertThat(again.bytesFreed()).isZero();
    }

    @Test
    void keepsASnapshotWhileTheNextDiffIsNotWritten() throws Exception {
        lake.delete(DiffStep.eventsKey(WEEKS.get(2)));
        PruneStep.Result result = new PruneStep(raw, lake, 4, 2).run();
        assertThat(result.lakePruned()).containsExactly(WEEKS.get(0));
        assertThat(result.kept()).containsOnlyKeys(WEEKS.get(1));
        assertThat(LakeCaptures.dates(lake)).isEqualTo(WEEKS.subList(1, 4));
    }

    @Test
    void keepsRawFilesTheLakeDoesNotHold() throws Exception {
        lake.delete(LakeLayout.parquetKey(SourceFile.OFFICERS, WEEKS.get(0)));
        PruneStep.Result result = new PruneStep(raw, lake, 1, 12).run();
        assertThat(result.rawPruned()).containsExactly(WEEKS.get(1), WEEKS.get(2));
        assertThat(result.kept()).containsOnlyKeys(WEEKS.get(0));
    }

    @Test
    void alwaysKeepsAtLeastOneCapture() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PruneStep(raw, lake, 0, 12));
        assertThat(PruneStep.due(WEEKS, 12)).isEmpty();
        assertThat(PruneStep.due(WEEKS, 1)).containsExactlyElementsOf(WEEKS.subList(0, 3));
    }

    private void capture(LocalDate date) throws Exception {
        raw.put(RawLayout.manifestKey(date), "{}".getBytes());
        for (SourceFile file : SourceFile.values()) {
            raw.put(RawLayout.csvKey(date, file), "csv".getBytes());
            lake.put(LakeLayout.parquetKey(file, date), "parquet".getBytes());
        }
        lake.put(DiffStep.eventsKey(date), new byte[0]);
        lake.put(QualityReport.key(date), "{}".getBytes());
    }
}
