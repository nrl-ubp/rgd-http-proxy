package com.ubp.rgd.proxy.resources;

import com.ubp.rgd.proxy.services.SecretsManagerResolver;
import com.ubp.rgd.proxy.services.TransformService;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.transform.api.TransformRequest;
import com.ubp.rgd.proxy.transform.api.TransformResponse;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

@Path("/transform")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Transform Resource", description = "Protect / Unprotect data through the RPS engine.")
public class TransformResource {

    private static final Logger LOG = LoggerFactory.getLogger(TransformResource.class);

    /**
     * The personalized security context containing user information from Kerberos (or Basic authz for local dev)
     */
    @Inject
    com.ubp.rgd.proxy.security.SecurityContext securityContext;

    /**
     * Prometheus metrics registry.
     */
    @Inject
    MeterRegistry metricsRegistry;

    @Inject
    TransformService transformService;

    @Inject
    SecretsManagerResolver secretsManagerResolver;

    /**
     * Duration timer to measure min, avg and max duration of transformation endpoint
     */
    private Timer durationTimer;

    @PostConstruct
    public void setupTimer() {
        this.durationTimer = Timer.builder("ubp_transform_duration")
                .description("Time taken by a transformation endpoint call.")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(metricsRegistry);
    }

    @POST
    @Operation(summary = "Transform (Protect / Unprotect) a list of value sets.",
            description = "Each set carries an action (e.g. Protect, Unprotect), a target (e.g. WDX1) and a jurisdiction " +
                    "code (CH, LU, MC, ...). Each value provides its class name and property name and, optionally, an " +
                    "extract-regex used to tokenize the value word by word (protection only) while preserving its format. " +
                    "Returns the transformed values grouped per set, in input order. The RPS secrets manager is " +
                    "selected by the proxy.transform.http-secrets-manager-header header (X-Proxy-Jurisdiction by " +
                    "default), or the default mapping when the header is absent.")
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Transformation success.",
                    content = @Content(schema = @Schema(implementation = TransformResponse.class))
            ),
            @APIResponse(
                    responseCode = "400",
                    description = "RPS Transformation error",
                    content = @Content(schema = @Schema(implementation = String.class))
            ),
            @APIResponse(
                    responseCode = "500",
                    description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = String.class))
            )
    })
    @Timed(
            value = "ubp_transform_duration",
            description = "Transform resource duration.",
            histogram = true
    )
    public TransformResponse transform(
            @RequestBody(required = true,
                         description = "Sample request payload",
                         content = @Content(
                            schema = @Schema(implementation = TransformRequest.class),
                                 examples = {
                                    @ExampleObject(
                                            name = "OnSaitJamais",
                                            value= """
                                                    {
                                                      "sets": [
                                                        {
                                                          "action": "Protect",
                                                          "target": "WDX1",
                                                          "module": "WDX1Proxy",
                                                          "jurisdiction": "LU",
                                                          "values": [
                                                            {
                                                              "value": "Jean-Claude DUSSE",
                                                              "class-name": "Person",
                                                              "property-name": "ShortString",
                                                              "extract-regex": "(?:\\\\w(?<!_)|~)+"
                                                            }
                                                          ]
                                                        }
                                                      ]
                                                    }
                                                    """
                                    )
                                 }
                         )
            )
            TransformRequest body,
           @Context UriInfo uriInfo,
           @Context HttpHeaders headers) throws RPSTransformException {

        // count the transformations
        body.getSets().forEach(transformSet -> {
            Objects.requireNonNull(metricsRegistry.counter("ubp_transform_counter", Tags.of("transformations", transformSet.getAction()))).increment(transformSet.getValues().size());
        });

        // used to get the top callers.
        String userName = securityContext.getToken() == null ? "NO_LOGIN" : securityContext.getToken().getUser();
        Objects.requireNonNull(metricsRegistry.counter("ubp_transform_counter", Tags.of("user", userName))).increment();

        int setCount = body.getSets() == null ? 0 : body.getSets().size();
        LOG.info("Transform request with {} set(s)", setCount);

        return transformService.transform(body, secretsManagerResolver.resolve(headers));
    }
}
