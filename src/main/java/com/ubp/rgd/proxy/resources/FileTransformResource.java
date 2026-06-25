package com.ubp.rgd.proxy.resources;

import com.ubp.rgd.proxy.services.FileTransformService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for triggering file transformations on-demand
 */
@Path("/api/file-transform")
@Produces(MediaType.APPLICATION_JSON)
public class FileTransformResource {

    private static final Logger LOG = Logger.getLogger(FileTransformResource.class);

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
     * Trigger processing for a specific configuration
     */
    @POST
    @Path("/trigger/{configName}")
    public Response triggerProcessing(@PathParam("configName") String configName) {
        try {
            LOG.infof("Triggering file transformation for configuration: %s", configName);
            int processedCount = fileTransformService.processConfigurationByName(configName);
            
            Map<String, Object> result = new HashMap<>();
            result.put("configuration", configName);
            result.put("filesProcessed", processedCount);
            result.put("status", "success");
            
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            LOG.warnf("Configuration not found: %s", configName);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Configuration not found: " + configName))
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to process configuration: %s", configName);
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
