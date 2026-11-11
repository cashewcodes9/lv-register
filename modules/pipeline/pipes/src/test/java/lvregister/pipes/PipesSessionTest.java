package lvregister.pipes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class PipesSessionTest {

    /** Written by dagster-pipes 1.13.28: {@code encode_param({"data": {...}})} for asset lake_snapshot. */
    static final String PYTHON_CONTEXT = "eJx1kMEKwkAMRH9FcrbQevDgr4iEtA262maXbKqW0n93t4Ki4jGZl5khE7RkBLvVBBQjG154jGncQ0cXxigU4skbHNYraHzLeGWNzgvWI74OEi9D1yUkqL+ykDT8DyA1Z9nguYZNudkWVVWUFXzLqCRHXrpFS8IvztJ+LecPE3M9481J62/vCjoIuuVOi8Xl7GsU6nMSNBRsUMbgAndOOOvKpiPK0NesiSlz8N2U8p+meZ4fyRRqFw==";
    /** {@code encode_param({"stdio": "stderr"})}: what PipesDockerLogsMessageReader passes. */
    static final String PYTHON_STDERR = "eJyrViouScnMV7JSADFSi4qUagFBNAaS";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void decodesTheParametersDagsterWrites() {
        JsonNode messages = PipesParams.decode(PYTHON_STDERR);
        assertThat(messages.get("stdio").asString()).isEqualTo("stderr");
        PipesContext context = PipesContext.fromJson(PipesParams.decode(PYTHON_CONTEXT).get("data"));
        assertThat(context).isEqualTo(new PipesContext(List.of("lake_snapshot"), Optional.of("2026-11-01"), "r-1"));
    }

    @Test
    void encodeAndDecodeRoundTrip() {
        Map<String, Object> value = Map.of("data", Map.of("asset_keys", List.of("a/b"), "run_id", "x"));
        assertThat(PipesParams.decode(PipesParams.encode(value)).get("data").get("asset_keys").get(0).asString())
                .isEqualTo("a/b");
    }

    @Test
    void noSessionWithoutTheBootstrapVariables() {
        assertThat(PipesSession.open(Map.of(), stream(out), stream(err))).isEmpty();
        assertThat(PipesSession.open(Map.of(PipesSession.CONTEXT_ENV, PYTHON_CONTEXT), stream(out), stream(err))).isEmpty();
    }

    @Test
    void writesOneJsonLinePerMessageToTheRequestedStream() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rows", 489_963L);
        metadata.put("share", 0.25);
        metadata.put("ok", true);
        metadata.put("file", "register");
        metadata.put("by_type", Map.of("NAME_CHANGED", 3L));
        metadata.put("uri", new PipesMetadata.Url("s3://lv-lake/register/"));
        try (PipesSession session = open()) {
            session.reportMaterialization(metadata);
            session.reportCheck("drop_share", false, Severity.ERROR, Map.of("dropped", 7L));
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        List<String> lines = err.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(4);
        assertThat(lines.getFirst()).isEqualTo(
                "{\"__dagster_pipes_version\":\"0.1\",\"method\":\"opened\",\"params\":{\"extras\":{}}}");
        assertThat(lines.get(1)).isEqualTo("{\"__dagster_pipes_version\":\"0.1\",\"method\":\"report_asset_materialization\","
                + "\"params\":{\"asset_key\":\"lake_snapshot\",\"data_version\":null,\"metadata\":{"
                + "\"rows\":{\"raw_value\":489963,\"type\":\"int\"},"
                + "\"share\":{\"raw_value\":0.25,\"type\":\"float\"},"
                + "\"ok\":{\"raw_value\":true,\"type\":\"bool\"},"
                + "\"file\":{\"raw_value\":\"register\",\"type\":\"text\"},"
                + "\"by_type\":{\"raw_value\":{\"NAME_CHANGED\":3},\"type\":\"json\"},"
                + "\"uri\":{\"raw_value\":\"s3://lv-lake/register/\",\"type\":\"url\"}}}}");
        JsonNode check = PipesParams.JSON.readTree(lines.get(2));
        assertThat(check.get("method").asString()).isEqualTo("report_asset_check");
        assertThat(check.get("params").get("check_name").asString()).isEqualTo("drop_share");
        assertThat(check.get("params").get("passed").asBoolean()).isFalse();
        assertThat(check.get("params").get("severity").asString()).isEqualTo("ERROR");
        assertThat(lines.get(3)).isEqualTo("{\"__dagster_pipes_version\":\"0.1\",\"method\":\"closed\",\"params\":{}}");
    }

    @Test
    void closesOnceAndRejectsLaterReports() {
        PipesSession session = open();
        session.close();
        session.close();
        assertThat(err.toString(StandardCharsets.UTF_8).lines()).hasSize(2);
        assertThatIllegalStateException().isThrownBy(() -> session.reportMaterialization(Map.of()));
    }

    @Test
    void reportsNeedASingleAssetInScope() {
        String context = PipesParams.encode(Map.of("data", Map.of("asset_keys", List.of("a", "b"), "run_id", "r")));
        PipesSession session = PipesSession.open(Map.of(PipesSession.CONTEXT_ENV, context,
                PipesSession.MESSAGES_ENV, PYTHON_STDERR), stream(out), stream(err)).orElseThrow();
        assertThatIllegalStateException().isThrownBy(() -> session.reportMaterialization(Map.of()));
    }

    @Test
    void readsTheContextFromAFileAndWritesMessagesToAFile(@TempDir Path dir) throws Exception {
        Path contextFile = dir.resolve("context.json");
        Files.writeString(contextFile, "{\"asset_keys\":[\"retention\"],\"partition_key\":null,\"run_id\":\"r-2\"}");
        Path messages = dir.resolve("messages.jsonl");
        try (PipesSession session = PipesSession.open(Map.of(
                PipesSession.CONTEXT_ENV, PipesParams.encode(Map.of("path", contextFile.toString())),
                PipesSession.MESSAGES_ENV, PipesParams.encode(Map.of("path", messages.toString()))), stream(out), stream(err))
                .orElseThrow()) {
            assertThat(session.context()).isEqualTo(new PipesContext(List.of("retention"), Optional.empty(), "r-2"));
            session.log("pruned 0 captures", "info");
        }
        assertThat(Files.readAllLines(messages)).hasSize(3);
    }

    private PipesSession open() {
        return PipesSession.open(Map.of(PipesSession.CONTEXT_ENV, PYTHON_CONTEXT, PipesSession.MESSAGES_ENV, PYTHON_STDERR),
                stream(out), stream(err)).orElseThrow();
    }

    private static PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }
}
