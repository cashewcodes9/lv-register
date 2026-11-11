package lvregister.ingest;

import java.net.URI;
import java.util.Optional;
import java.util.OptionalLong;

/** A UR file as listed in the CKAN catalog of data.gov.lv. */
public record CatalogEntry(SourceFile file, URI url, Optional<String> lastModified, OptionalLong size) {}
