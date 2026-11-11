package lvregister.serving;

/**
 * One result of a value feature from the dbt marts, e.g. feature
 * {@code mass_registration_addresses}: key = VZD address id, rank 1 = most striking, and the
 * mart row as a JSON object.
 */
public record FeatureView(String feature, String key, int rank, String json) {}
