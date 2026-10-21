package com.ubp.rgd.proxy.resources;

import com.ubp.rgd.proxy.services.SecretsManagerResolver;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Declares, in the OpenAPI document and thus in the Swagger UI, the header selecting the secrets manager
 * of {@code POST /utils/wdx1/concat}.
 * <p>
 * The endpoint reads the header from {@code HttpHeaders} because its name is configurable
 * ({@code proxy.transform.http-secrets-manager-header}), so it cannot be declared with an annotation.
 * This filter runs when the application starts and takes the name, the accepted values and the default
 * from {@link SecretsManagerResolver}. The field is free text, so that an unknown value can be tried too.
 */
@OpenApiFilter(OpenApiFilter.RunStage.RUN)
public class SecretsManagerHeaderOpenApiFilter implements OASFilter {

    private static final Logger LOG = LoggerFactory.getLogger(SecretsManagerHeaderOpenApiFilter.class);

    static final String CONCAT_PATH = "/utils/wdx1/concat";

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (openAPI.getPaths() == null || openAPI.getPaths().getPathItems() == null) {
            return;
        }
        Operation concat = openAPI.getPaths().getPathItems().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(CONCAT_PATH))
                .map(Map.Entry::getValue)
                .map(PathItem::getPOST)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (concat == null) {
            LOG.debug("No POST {} operation in the OpenAPI document, the secrets manager header is not declared",
                    CONCAT_PATH);
            return;
        }

        try (InstanceHandle<SecretsManagerResolver> handle = Arc.container().instance(SecretsManagerResolver.class)) {
            if (!handle.isAvailable()) {
                LOG.debug("No SecretsManagerResolver, the secrets manager header is not declared");
                return;
            }
            addHeader(concat, handle.get());
        }
    }

    static void addHeader(Operation operation, SecretsManagerResolver resolver) {
        String headerName = resolver.headerName();
        boolean declared = operation.getParameters() != null && operation.getParameters().stream()
                .anyMatch(p -> p.getIn() == Parameter.In.HEADER && headerName.equalsIgnoreCase(p.getName()));
        if (declared) {
            return;
        }

        operation.addParameter(OASFactory.createParameter()
                .name(headerName)
                .in(Parameter.In.HEADER)
                .required(false)
                .description(String.format(
                        "Jurisdiction selecting the secrets manager used to read the input tokens and to tokenize"
                                + " the result. Accepted values (case-insensitive): %s. Left empty, %s is used."
                                + " Any other value is rejected with a 400.",
                        String.join(", ", resolver.jurisdictions()), resolver.defaultMapping()))
                .schema(OASFactory.createSchema().addType(Schema.SchemaType.STRING))
                .example(resolver.defaultMapping()));
    }
}
