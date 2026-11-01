package lvregister.cli;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import lvregister.diff.DiffStep;
import lvregister.index.IndexStep;
import lvregister.index.MartReader;
import lvregister.ingest.CkanCatalog;
import lvregister.ingest.Downloader;
import lvregister.ingest.Http;
import lvregister.lake.DuckDb;
import lvregister.lake.DuckDbSettings;
import lvregister.lake.LakeReader;
import lvregister.lake.LakeWriter;
import lvregister.lake.SnapshotLoader;
import lvregister.quality.QualityReport;
import lvregister.quality.QualityStep;
import lvregister.quality.Thresholds;
import lvregister.serving.postgres.PostgresServing;
import lvregister.serving.postgres.PostgresSettings;
import lvregister.storage.ObjectStore;
import lvregister.storage.S3ObjectStore;
import lvregister.storage.S3Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Manual wiring of the real steps from environment variables (see {@code .env.example}).
 * Clients are created when a step runs, so {@code --help} needs no configuration.
 */
final class Wiring {

    private static final Logger LOG = LoggerFactory.getLogger(Wiring.class);

    private Wiring() {}

    static Steps production(Map<String, String> env, RunContext context) {
        Map<StepName, Step> steps = new EnumMap<>(Steps.notImplemented().byName());
        steps.put(StepName.DOWNLOAD, date -> {
            try (S3Client s3 = S3ObjectStore.client(S3Settings.fromEnvironment(env))) {
                Downloader downloader = new Downloader(Http.defaults(), catalogUrl(env), raw(env, s3),
                        workDir(env).resolve("download"), Clock.systemUTC());
                Downloader.Result result = downloader.run(date);
                LOG.info("download: {} files stored ({} bytes), {} already in the capture",
                        result.downloaded(), result.bytesDownloaded(), result.skipped());
            }
        });
        steps.put(StepName.LAKE, date -> {
            S3Settings settings = S3Settings.fromEnvironment(env);
            try (S3Client s3 = S3ObjectStore.client(settings);
                    DuckDb db = DuckDb.open(DuckDbSettings.fromEnvironment(env), Optional.of(settings))) {
                var results = new LakeWriter(db, raw(env, s3), lake(env, s3), workDir(env).resolve("lake")).run(date);
                LOG.info("lake: {} files written, {} unchanged",
                        results.stream().filter(LakeWriter.FileResult::written).count(),
                        results.stream().filter(r -> !r.written()).count());
            }
        });
        steps.put(StepName.DIFF, date -> {
            S3Settings settings = S3Settings.fromEnvironment(env);
            try (S3Client s3 = S3ObjectStore.client(settings);
                    DuckDb db = DuckDb.open(DuckDbSettings.fromEnvironment(env), Optional.of(settings))) {
                ObjectStore lake = lake(env, s3);
                DiffStep.Result result = new DiffStep(lake, new SnapshotLoader(new LakeReader(db, lake))).run(date);
                LOG.info("diff: {} events against {}", result.total(),
                        result.previous().map(Object::toString).orElse("nothing (baseline)"));
            }
        });
        steps.put(StepName.QUALITY, date -> {
            S3Settings settings = S3Settings.fromEnvironment(env);
            try (S3Client s3 = S3ObjectStore.client(settings);
                    DuckDb db = DuckDb.open(DuckDbSettings.fromEnvironment(env), Optional.of(settings))) {
                ObjectStore raw = raw(env, s3);
                ObjectStore lake = lake(env, s3);
                Map<String, ObjectStore> buckets = Map.of(
                        bucket(env, "S3_BUCKET_RAW", "lv-raw"), raw,
                        bucket(env, "S3_BUCKET_LAKE", "lv-lake"), lake,
                        bucket(env, "S3_BUCKET_MARTS", "lv-marts"), new S3ObjectStore(s3, bucket(env, "S3_BUCKET_MARTS", "lv-marts")));
                QualityReport report = new QualityStep(db, raw, lake, buckets, Thresholds.fromEnvironment(env), Clock.systemUTC())
                        .run(date, context.runId(), context.dagsterRunId());
                LOG.info("quality: {}", report.status());
            }
        });
        steps.put(StepName.INDEX, date -> {
            S3Settings settings = S3Settings.fromEnvironment(env);
            try (S3Client s3 = S3ObjectStore.client(settings);
                    DuckDb db = DuckDb.open(DuckDbSettings.fromEnvironment(env), Optional.of(settings));
                    var serving = servingStore(env)) {
                ObjectStore lake = lake(env, s3);
                ObjectStore marts = new S3ObjectStore(s3, bucket(env, "S3_BUCKET_MARTS", "lv-marts"));
                new IndexStep(lake, new SnapshotLoader(new LakeReader(db, lake)), serving, new MartReader(db, marts)).run(date);
            }
        });
        return new Steps(steps);
    }

    /** The serving backend behind the port, chosen by {@code SERVING_BACKEND} (PLAN.md section 4). */
    static PostgresServing servingStore(Map<String, String> env) {
        String backend = env.getOrDefault("SERVING_BACKEND", "postgres");
        if (!backend.equals("postgres")) {
            throw new IllegalArgumentException("unknown SERVING_BACKEND '" + backend + "': only postgres is implemented");
        }
        return PostgresServing.connect(PostgresSettings.fromEnvironment(env), 2);
    }

    private static String bucket(Map<String, String> env, String variable, String fallback) {
        return env.getOrDefault(variable, fallback);
    }

    private static ObjectStore raw(Map<String, String> env, S3Client s3) {
        return new S3ObjectStore(s3, env.getOrDefault("S3_BUCKET_RAW", "lv-raw"));
    }

    private static ObjectStore lake(Map<String, String> env, S3Client s3) {
        return new S3ObjectStore(s3, env.getOrDefault("S3_BUCKET_LAKE", "lv-lake"));
    }

    private static URI catalogUrl(Map<String, String> env) {
        return Optional.ofNullable(env.get("UR_CKAN_URL")).filter(s -> !s.isBlank()).map(URI::create)
                .orElse(CkanCatalog.DEFAULT_URL);
    }

    private static Path workDir(Map<String, String> env) {
        return Path.of(env.getOrDefault("LV_WORK_DIR", System.getProperty("java.io.tmpdir") + "/lv-register"));
    }
}
