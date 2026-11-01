package lvregister.api;

import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lvregister.api.dto.Dtos;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the spec say exactly what the JSON holds, so the generated TypeScript types are exact:
 * every DTO field is required, and a field annotated {@link Nullable} may be {@code null}.
 * Without this, springdoc marks every field optional.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static {
        // Stored JSON (event payloads, quality reports, feature rows) is a free-form object, not the
        // Jackson JsonNode class.
        org.springdoc.core.utils.SpringDocUtils.getConfig().replaceWithSchema(tools.jackson.databind.JsonNode.class,
                new io.swagger.v3.oas.models.media.ObjectSchema().additionalProperties(true));
    }

    @Bean
    // swagger-models returns raw Schema maps (getProperties), so raw types cannot be avoided here.
    @SuppressWarnings({"rawtypes", "unchecked"})
    OpenApiCustomizer requiredAndNullableFields() {
        Map<String, Set<String>> nullableBySchema = new HashMap<>();
        for (Class<?> dto : Dtos.class.getDeclaredClasses()) {
            if (dto.isRecord()) {
                nullableBySchema.put(dto.getSimpleName(), Arrays.stream(dto.getRecordComponents())
                        .filter(c -> c.getAnnotatedType().isAnnotationPresent(Nullable.class))
                        .map(RecordComponent::getName).collect(Collectors.toSet()));
            }
        }
        return openApi -> openApi.getComponents().getSchemas().forEach((name, schema) -> {
            Map<String, Schema> properties = schema.getProperties();
            if (properties == null || !name.endsWith("Dto")) {
                return;
            }
            Set<String> nullable = nullableBySchema.getOrDefault(name, Set.of());
            List<String> required = new ArrayList<>();
            properties.forEach((property, propertySchema) -> {
                if (nullable.contains(property)) {
                    if (propertySchema.get$ref() != null) {
                        Schema<Object> ref = new Schema<>().$ref(propertySchema.get$ref());
                        Schema<Object> none = new Schema<>();
                        none.addType("null");
                        properties.put(property, new Schema<>().anyOf(List.of(ref, none)));
                    } else {
                        propertySchema.addType("null");
                    }
                }
                required.add(property);
            });
            schema.setRequired(required.stream().sorted().toList());
        });
    }
}
