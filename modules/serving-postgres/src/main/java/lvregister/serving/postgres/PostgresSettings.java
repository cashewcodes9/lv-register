package lvregister.serving.postgres;

import java.util.Map;

/** Connection to the serving database. {@code SERVING_JDBC_URL}, {@code SERVING_DB_USER}, {@code SERVING_DB_PASSWORD}. */
public record PostgresSettings(String jdbcUrl, String user, String password) {

    public PostgresSettings {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:") || user == null || user.isBlank() || password == null) {
            throw new IllegalArgumentException("serving database needs a jdbc:postgresql: URL, a user and a password");
        }
    }

    public static PostgresSettings fromEnvironment(Map<String, String> env) {
        return new PostgresSettings(
                env.getOrDefault("SERVING_JDBC_URL", "jdbc:postgresql://postgres:5432/lv_serving"),
                env.getOrDefault("SERVING_DB_USER", "lv_serving"),
                env.getOrDefault("SERVING_DB_PASSWORD", "lv-serving-local-only"));
    }

    @Override
    public String toString() {
        return "PostgresSettings[jdbcUrl=" + jdbcUrl + ", user=" + user + ", password=***]";
    }
}
