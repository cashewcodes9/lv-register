package lvregister.lake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lvregister.ingest.SourceFile;
import lvregister.storage.ObjectStore;
import org.junit.jupiter.api.Test;

/** Lake step behaviour; subclasses provide the stores and the DuckDB session. */
abstract class LakeContract {

    static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    abstract ObjectStore raw();

    abstract ObjectStore lake();

    abstract DuckDb db();

    abstract java.nio.file.Path workDir();

    private LakeWriter writer() {
        return new LakeWriter(db(), raw(), lake(), workDir());
    }

    @Test
    void writesOneParquetFilePerSourceFile() throws Exception {
        RawCaptures.write(raw(), DAY, Map.of());

        List<LakeWriter.FileResult> results = writer().run(DAY);

        assertThat(results).hasSize(SourceFile.values().length).allSatisfy(r -> assertThat(r.written()).isTrue());
        assertThat(results.getFirst()).isEqualTo(new LakeWriter.FileResult(SourceFile.REGISTER, 2, true));
        assertThat(lake().list("register/")).containsExactly("register/date=2026-11-02/register.parquet");
        assertThat(lake().list("_tmp/")).isEmpty();
    }

    @Test
    void listsTheColumnsOfAFileInFileOrder() throws Exception {
        RawCaptures.write(raw(), DAY, Map.of());
        writer().run(DAY);

        assertThat(new LakeReader(db(), lake()).columns(SourceFile.REGISTER, DAY)).startsWith("regcode").contains("name", "type");
    }

    @Test
    void keepsTheDialectQuirksAsText() throws Exception {
        RawCaptures.write(raw(), DAY, Map.of());
        writer().run(DAY);

        List<Map<String, String>> rows;
        try (Stream<Map<String, String>> s = new LakeReader(db(), lake())
                .rows(SourceFile.REGISTER, DAY, List.of("regcode", "name", "type", "closed", "address", "grounds"))) {
            rows = s.toList();
        }

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("regcode", "012345678")
                .containsEntry("name", "SIA \"Alfa\"")
                .containsEntry("closed", " ")
                .containsEntry("address", "Rīga, Brīvības iela 1")
                .doesNotContainKey("grounds");
        assertThat(rows.get(1))
                .containsEntry("regcode", "40003000001")
                .containsEntry("closed", "L")
                .containsEntry("grounds", "line one\nline two")
                .doesNotContainKey("address");
    }

    @Test
    void storesTheSourceSha256AndDoesNotAddADateColumn() throws Exception {
        RawCaptures.write(raw(), DAY, Map.of());
        writer().run(DAY);
        String parquet = DuckDb.literal(lake().location(LakeLayout.parquetKey(SourceFile.REGISTER, DAY)));

        assertThat(writer().sourceSha256(LakeLayout.parquetKey(SourceFile.REGISTER, DAY)))
                .contains(RawCaptures.sha256(RawCaptures.REGISTER.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(db().queryString("SELECT string_agg(column_name, ',' ORDER BY column_name) FROM "
                + "(DESCRIBE SELECT * FROM read_parquet(" + parquet + ", hive_partitioning = false))"))
                .contains("address,closed,grounds,name,regcode,type");
        assertThat(db().queryString("SELECT string_agg(DISTINCT column_type, ',') FROM "
                + "(DESCRIBE SELECT * FROM read_parquet(" + parquet + ", hive_partitioning = false))")).contains("VARCHAR");
    }

    @Test
    void secondRunWritesNothing() throws Exception {
        RawCaptures.write(raw(), DAY, Map.of());
        writer().run(DAY);

        List<LakeWriter.FileResult> again = writer().run(DAY);

        assertThat(again).allSatisfy(r -> assertThat(r.written()).isFalse());
        assertThat(again.getFirst().rows()).isEqualTo(2);
    }

    @Test
    void newRawContentReplacesTheParquetFile() throws Exception {
        RawCaptures.write(raw(), DAY, Map.of());
        writer().run(DAY);
        RawCaptures.write(raw(), DAY, Map.of(SourceFile.OFFICERS, "id;value\n1;a\n2;b\n3;c\n"));

        List<LakeWriter.FileResult> again = writer().run(DAY);

        assertThat(again).filteredOn(LakeWriter.FileResult::written)
                .containsExactly(new LakeWriter.FileResult(SourceFile.OFFICERS, 3, true));
    }

    @Test
    void failsWithoutARawCapture() {
        assertThatIllegalStateException().isThrownBy(() -> writer().run(DAY)).withMessageContaining("run download first");
    }
}
