package lvregister.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lvregister.diff.SyntheticCaptures;
import lvregister.ingest.SourceFile;
import lvregister.serving.postgres.PostgresServing;
import lvregister.serving.postgres.PostgresSettings;
import lvregister.storage.S3ObjectStore;
import lvregister.storage.S3Settings;
import lvregister.storage.SeaweedFs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The CLI end to end on synthetic data: download → lake → diff → quality → index for two
 * captures, against SeaweedFS, PostgreSQL and a fake data.gov.lv. Then the log output (as the
 * JSON encoder writes it) must not contain any person data (PLAN.md section 12).
 */
class PipelineLogsTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 9);
    private static final List<String> MASKED_CODES = List.of("010101-*****", "020202-*****");

    private static PostgreSQLContainer postgres;
    private static HttpServer ur;
    private static final Map<String, String> served = new HashMap<>();

    @TempDir
    static Path tmp;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer("postgres:18.6-alpine3.24");
        postgres.start();
        ur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ur.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.equals("/catalog") ? catalog().getBytes(StandardCharsets.UTF_8)
                    : served.getOrDefault(path.substring(1), "").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        ur.start();
    }

    @AfterAll
    static void stop() {
        ur.stop(0);
        postgres.stop();
    }

    private static String catalog() {
        String base = "http://127.0.0.1:" + ur.getAddress().getPort() + "/";
        String resources = served.keySet().stream().map(name -> "{\"url\": \"" + base + name + "\"}").collect(Collectors.joining(","));
        return "{\"success\": true, \"result\": {\"results\": [{\"resources\": [" + resources + "]}]}}";
    }

    private static void serve(Map<SourceFile, String> files) {
        served.clear();
        files.forEach((file, csv) -> served.put(file.csvName(), csv));
    }

    @Test
    void fullRunLeavesNoPersonDataInTheLogs() throws Exception {
        S3Settings s3 = SeaweedFs.settings();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> env = new HashMap<>(Map.of(
                "S3_ENDPOINT", s3.endpoint().toString(), "S3_ACCESS_KEY", s3.accessKey(), "S3_SECRET_KEY", s3.secretKey(),
                "S3_BUCKET_RAW", "raw-" + suffix, "S3_BUCKET_LAKE", "lake-" + suffix, "S3_BUCKET_MARTS", "marts-" + suffix,
                "UR_CKAN_URL", "http://127.0.0.1:" + ur.getAddress().getPort() + "/catalog",
                "LV_WORK_DIR", tmp.resolve("work").toString(), "DUCKDB_MEMORY_LIMIT", "256MB",
                "DUCKDB_TEMP_DIRECTORY", tmp.resolve("duckdb").toString()));
        env.putAll(Map.of("SERVING_JDBC_URL", postgres.getJdbcUrl(), "SERVING_DB_USER", postgres.getUsername(),
                "SERVING_DB_PASSWORD", postgres.getPassword()));
        try (S3Client client = S3ObjectStore.client(s3)) {
            for (String bucket : List.of("raw-", "lake-", "marts-")) {
                SeaweedFs.createBucket(client, bucket + suffix);
            }
        }
        RunContext context = new RunContext("e2e-run", Optional.empty());
        Steps steps = Wiring.production(env, context);
        List<String> logs = captureLogs(() -> {
            serve(SyntheticCaptures.files(SyntheticCaptures.COMPANY_A + SyntheticCaptures.COMPANY_B,
                    SyntheticCaptures.OFFICER_A + SyntheticCaptures.OFFICER_B));
            for (String step : List.of("download", "lake", "diff", "quality", "index")) {
                assertThat(Main.newCommandLine(steps, context).execute(step, "--date", D1.toString())).as(step).isZero();
            }
            serve(SyntheticCaptures.files(SyntheticCaptures.COMPANY_A.replace("Alfa", "Alfa Plus") + SyntheticCaptures.COMPANY_B,
                    SyntheticCaptures.OFFICER_A
                            + "o3;40003000001;NATURAL_PERSON;CHAIR_OF_BOARD;EXECUTIVE_BOARD;Other Person;020202-*****;;;2026-11-03\n"));
            for (String step : List.of("download", "lake", "diff", "quality", "index")) {
                assertThat(Main.newCommandLine(steps, context).execute(step, "--date", D2.toString())).as(step).isZero();
            }
        });

        try (PostgresServing serving = PostgresServing.connect(PostgresSettings.fromEnvironment(env), 1)) {
            assertThat(serving.company("40003000001")).hasValueSatisfying(c -> assertThat(c.name()).isEqualTo("SIA \"Alfa Plus\""));
            assertThat(serving.eventsOf("40003000001", 0, 10).items()).extracting(e -> e.type())
                    .containsExactlyInAnyOrder("NAME_CHANGED", "MANAGEMENT_CHANGED");
            assertThat(serving.runs(0, 10).total()).isEqualTo(1);
            assertThat(serving.search("alfa plus", 0, 5).items()).isNotEmpty();
        }
        assertThat(logs).isNotEmpty().anySatisfy(l -> assertThat(l).contains("\"step\":\"index\"", "\"run_id\":\"e2e-run\""));
        List<String> forbidden = new java.util.ArrayList<>(SyntheticCaptures.PERSON_NAMES);
        forbidden.addAll(MASKED_CODES);
        for (String line : logs) {
            for (String word : forbidden) {
                assertThat(line).as("log line must not contain person data").doesNotContain(word);
            }
        }
    }

    /** Runs the work and returns every log line as the STDOUT encoder writes it. */
    private static List<String> captureLogs(ThrowingRunnable work) throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        root.addAppender(appender);
        try {
            work.run();
        } finally {
            root.detachAppender(appender);
        }
        @SuppressWarnings("unchecked")
        var encoder = ((OutputStreamAppender<ILoggingEvent>) root.getAppender("STDOUT")).getEncoder();
        return appender.list.stream().map(e -> new String(encoder.encode(e), StandardCharsets.UTF_8)).toList();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
