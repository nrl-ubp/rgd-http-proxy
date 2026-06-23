package com.ubp.rgd.proxy;

import com.ubp.rgd.proxy.services.wdx1.WDX1ConcatRequest;
import com.ubp.rgd.proxy.services.wdx1.WDX1ConcatResponse;
import com.ubp.rgd.proxy.services.wdx1.WDX1UtilsService;
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

@Path("/utils")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Token utils Resource", description = "Utility resource providing operators on tokens")
public class TokenUtilsResource {

    private static final Logger LOG = Logger.getLogger(TokenUtilsResource.class);

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
    WDX1UtilsService WDX1UtilsService;

    @POST
    @Path("/wdx1/concat")
    @Operation(summary = "Generate a token representing the formatting of the payload in the form: CCYYYYMMDDAAAAABBBBB.", description = "Returns the RPS token for the CCYYYYMMDDAAAAABBBBB payload formatting. Where CC is the nationality country code, YYYYMMDD is the date of birth, AAAAA are the 5 first characters of the first name and BBBBB are the 5 first characters of the surname. If first names and surnames are shorter, replaces characters by #. The result is the corresponding RPS token.")
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "CONCAT success.",
                    content = @Content(schema = @Schema(implementation = WDX1ConcatResponse.class))
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
    public WDX1ConcatResponse concat(WDX1ConcatRequest body,
                                     @Context UriInfo uriInfo,
                                     @Context HttpHeaders headers) throws Exception {
        // prometheus increment counter specific to this endpoint
        Objects.requireNonNull(metricsRegistry.counter("ubp_proxy_counter", Tags.of("name", "get_concat"))).increment();

        String concatToken = WDX1UtilsService.tokenConcat(body);
        WDX1ConcatResponse response = new WDX1ConcatResponse();
        response.setConcatResponseToken(concatToken);
        return response;
    }
}
