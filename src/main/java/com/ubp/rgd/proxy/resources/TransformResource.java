package com.ubp.rgd.proxy.resources;

import com.ubp.rgd.proxy.services.TransformService;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.transform.api.TransformRequest;
import com.ubp.rgd.proxy.transform.api.TransformResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.Objects;

@Path("/transform")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Transform Resource", description = "Protect / Unprotect data through the RPS engine.")
public class TransformResource {

    private static final Logger LOG = Logger.getLogger(TransformResource.class);

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

    @POST
    @Operation(summary = "Transform (Protect / Unprotect) a list of value sets.",
            description = "Each set carries an action (e.g. Protect, Unprotect), a target (e.g. WDX1) and a jurisdiction " +
                    "code (CH, LU, MC, ...). Each value provides its class name and property name and, optionally, an " +
                    "extract-regex used to tokenize the value word by word (protection only) while preserving its format. " +
                    "Returns the transformed values grouped per set, in input order.")
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
    public TransformResponse transform(TransformRequest body,
                                       @Context UriInfo uriInfo,
                                       @Context HttpHeaders headers) throws RPSTransformException {
        Objects.requireNonNull(metricsRegistry.counter("ubp_proxy_counter", Tags.of("name", "transform"))).increment();

        int setCount = body == null || body.getSets() == null ? 0 : body.getSets().size();
        LOG.infof("Transform request with %d set(s)", setCount);

        return transformService.transform(body);
    }
}
