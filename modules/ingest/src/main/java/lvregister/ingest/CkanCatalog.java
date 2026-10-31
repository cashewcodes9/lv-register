package lvregister.ingest;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the download URL of each MVP file in a CKAN {@code package_search} response for the
 * organisation {@code ur}. Files are matched by the last path segment of the resource URL.
 */
public final class CkanCatalog {

    /** Default: all packages of the UR organisation on data.gov.lv. */
    public static final URI DEFAULT_URL =
            URI.create("https://data.gov.lv/dati/api/3/action/package_search?fq=organization:ur&rows=200");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CkanCatalog() {}

    /** Pure: parses the response and returns one entry per MVP file, or throws if any is missing. */
    public static Map<SourceFile, CatalogEntry> parse(byte[] packageSearchJson) {
        JsonNode root = JSON.readTree(packageSearchJson);
        if (!root.path("success").asBoolean(false)) {
            throw new IllegalStateException("CKAN package_search did not succeed");
        }
        Map<SourceFile, CatalogEntry> found = new EnumMap<>(SourceFile.class);
        List<String> duplicates = new ArrayList<>();
        for (JsonNode pkg : root.path("result").path("results")) {
            for (JsonNode resource : pkg.path("resources")) {
                String url = resource.path("url").asString("");
                String name = url.substring(url.lastIndexOf('/') + 1);
                for (SourceFile file : SourceFile.values()) {
                    if (file.csvName().equals(name)) {
                        CatalogEntry entry = new CatalogEntry(file, URI.create(url),
                                text(resource, "last_modified"), number(resource, "size"));
                        if (found.putIfAbsent(file, entry) != null) {
                            duplicates.add(name);
                        }
                    }
                }
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("files listed more than once in the CKAN catalog: " + duplicates);
        }
        List<String> missing = new ArrayList<>();
        for (SourceFile file : SourceFile.values()) {
            if (!found.containsKey(file)) {
                missing.add(file.csvName());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("files missing from the CKAN catalog: " + missing);
        }
        return found;
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? Optional.of(value.asString()) : Optional.empty();
    }

    private static OptionalLong number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? OptionalLong.of(value.asLong()) : OptionalLong.empty();
    }
}
