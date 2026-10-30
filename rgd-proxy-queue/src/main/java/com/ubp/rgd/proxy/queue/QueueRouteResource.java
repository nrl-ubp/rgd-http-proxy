package com.ubp.rgd.proxy.queue;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * The state and counters of the queue proxy routes. Empty when the queue proxy is disabled.
 */
@Path("/api/queue/routes")
@Produces(MediaType.APPLICATION_JSON)
public class QueueRouteResource {

    @Inject
    QueueProxyService service;

    @GET
    public List<QueueRouteStatus> routes() {
        return service.routes().stream().map(QueueRoute::status).toList();
    }

    @GET
    @Path("/{name}")
    public QueueRouteStatus route(@PathParam("name") String name) {
        return service.route(name).map(QueueRoute::status)
                .orElseThrow(() -> new NotFoundException("No queue route named " + name));
    }
}
