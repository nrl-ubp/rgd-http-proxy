package com.ubp.rgd.proxy.resources;

import com.ubp.rgd.proxy.services.ProxyService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.Objects;

@Path("/proxy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Proxy Resource", description = "Check authz and forward request to configured target site.")
public class ProxyResource {

    private static final Logger LOG = Logger.getLogger(ProxyResource.class);

    /**
     * Service implementation of the resource.
     */
    @Inject
    ProxyService proxyService;

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
    @Path("/{path:.*}")
    public Response proxyGet(@PathParam("path") String path,
                             @Context UriInfo uriInfo,
                             @Context HttpHeaders headers) {
        LOG.infof("Proxy GET request for path: %s", path);
        Objects.requireNonNull(metricsRegistry.counter("ubp_proxy_counter", Tags.of("name", "get"))).increment();
        return proxyService.forwardRequest("GET", path, null, uriInfo, headers);
    }

    @POST
    @Path("/{path:.*}")
    public Response proxyPost(@PathParam("path") String path,
                              String body,
                              @Context UriInfo uriInfo,
                              @Context HttpHeaders headers) {
        LOG.infof("Proxy POST request for path: %s", path);
        Objects.requireNonNull(metricsRegistry.counter("ubp_proxy_counter", Tags.of("name", "post"))).increment();
        return proxyService.forwardRequest("POST", path, body, uriInfo, headers);
    }

    @PUT
    @Path("/{path:.*}")
    public Response proxyPut(@PathParam("path") String path,
                             String body,
                             @Context UriInfo uriInfo,
                             @Context HttpHeaders headers) {
        LOG.infof("Proxy PUT request for path: %s", path);
        Objects.requireNonNull(metricsRegistry.counter("ubp_proxy_counter", Tags.of("name", "put"))).increment();
        return proxyService.forwardRequest("PUT", path, body, uriInfo, headers);
    }

    @DELETE
    @Path("/{path:.*}")
    public Response proxyDelete(@PathParam("path") String path,
                                @Context UriInfo uriInfo,
                                @Context HttpHeaders headers) {
        LOG.infof("Proxy DELETE request for path: %s", path);
        Objects.requireNonNull(metricsRegistry.counter("ubp_proxy_counter", Tags.of("name", "delete"))).increment();
        return proxyService.forwardRequest("DELETE", path, null, uriInfo, headers);
    }
}