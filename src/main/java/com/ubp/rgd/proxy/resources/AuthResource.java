package com.ubp.rgd.proxy.resources;


import com.ubp.rgd.proxy.utils.JSONFile;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Objects;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Auth Resource", description = "Utility resource providing auth information on BASIC and Kerberos negotiate schemes.")
public class AuthResource {
    private static final Logger LOG = Logger.getLogger(AuthResource.class);

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

    @GET
    @Path("/me")
    @Operation(summary = "Get client Kerberos info", description = "Get the current user's auth configuration using kerberos.")
    public Response me() {
        // prometheus increment counter specific to this endpoint
        Objects.requireNonNull(metricsRegistry.counter("ubp_proxy_counter", Tags.of("name", "get_me"))).increment();

        if ((securityContext != null) && (securityContext.getToken() != null)) {
            try {
                return Response.status(200).entity(JSONFile.serialize(securityContext.getToken())).build();
            } catch (IOException e) {
                return Response.serverError().entity("Cannot serialize security context's token." + e.getMessage()).build();
            }
        } else {
            return Response.status(200).entity("anonymous").build();
        }
    }
}
