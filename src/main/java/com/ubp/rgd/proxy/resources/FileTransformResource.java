package com.ubp.rgd.proxy.resources;

import com.ubp.rgd.proxy.services.FileTransformResult;
import com.ubp.rgd.proxy.services.FileTransformService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for triggering file transformations on-demand
 */
@Path("/api/file-transform")
@Produces(MediaType.APPLICATION_JSON)
public class FileTransformResource {

    private static final Logger LOG = LoggerFactory.getLogger(FileTransformResource.class);

    @Inject
    FileTransformService fileTransformService;

    /**
     * Get list of all available configurations
     */
    @GET
    @Path("/configurations")
    public Response getConfigurations() {
        try {
            List<String> configNames = fileTransformService.getConfigurationNames();
            Map<String, Object> result = new HashMap<>();
            result.put("configurations", configNames);
            result.put("count", configNames.size());
            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.error("Failed to get configurations", e);
            return Response.serverError()
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * Trigger processing for a specific configuration.
     * <p>
     * The call is synchronous: it returns once every file has been dealt with. The response carries
     * the outcome of the run, and the status is <b>500</b> as soon as a single file failed, so that a
     * batch driving this endpoint cannot mistake a failed run for a successful one.
     */
    @POST
    @Path("/trigger/{configName}")
    public Response triggerProcessing(@PathParam("configName") String configName) {
        try {
            LOG.info("Triggering file transformation for configuration: {}", configName);
            FileTransformResult result = fileTransformService.processConfigurationByName(configName);

            Map<String, Object> entity = new HashMap<>();
            entity.put("configuration", result.configuration());
            entity.put("filesProcessed", result.filesProcessed());
            entity.put("filesSucceeded", result.filesSucceeded());
            entity.put("filesFailed", result.filesFailed());
            entity.put("errors", result.errors());
            entity.put("status", result.status());

            if (result.hasFailures()) {
                LOG.error("Configuration '{}' finished with failures: {}", configName, result.errors());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(entity).build();
            }

            return Response.ok(entity).build();
        } catch (IllegalArgumentException e) {
            LOG.warn("Configuration not found: {}", configName);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Configuration not found: " + configName))
                    .build();
        } catch (IllegalStateException e) {
            // The configuration is driven by the scheduler: triggering it would run it twice at once.
            LOG.warn("Configuration cannot be triggered: {}", e.getMessage());
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to process configuration: {}", configName, e);
            return Response.serverError()
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * Get details of a specific configuration
     */
    @GET
    @Path("/configuration/{configName}")
    public Response getConfiguration(@PathParam("configName") String configName) {
        try {
            var config = fileTransformService.getConfigurationByName(configName);
            if (config == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Configuration not found: " + configName))
                        .build();
            }

            Map<String, Object> result = new HashMap<>();
            result.put("name", config.getName());
            result.put("sourceDirectory", config.getSourceDirectory());
            result.put("targetDirectory", config.getTargetDirectory());
            result.put("errorDirectory", config.getErrorDirectory());
            result.put("scanIntervalSeconds", config.getScanIntervalSeconds());
            result.put("filePattern", config.getFilePattern());
            result.put("preserveDirectoryStructure", config.isPreserveDirectoryStructure());
            
            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.error("Failed to get configuration details", e);
            return Response.serverError()
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }
}
