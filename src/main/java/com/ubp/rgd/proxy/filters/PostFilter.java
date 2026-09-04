package com.ubp.rgd.proxy.filters;

import com.ubp.rgd.proxy.security.SecurityContext;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.RPSEndPointTransformer;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The PostFilter will actually make the RegData transformation depending on the configuration
 * of the data structure and the rules to apply.
 * Please note that there is no authorization management here since we trust the caller to apply restrictions
 * on the data being traversed by this proxy.
 */
@Provider
public class PostFilter implements ContainerResponseFilter {
    private static final Logger LOG = Logger.getLogger(PostFilter.class);

    /**
     * Security context can be used to configure the authorizations for the transformers
     * depending on the implementations
     */
    @Inject
    private SecurityContext securityContext;

    @Inject
    RPSEndPointTransformer endPointTransformer;

    /**
     * Whether a caller is allowed to skip the RPS transformation with the
     * {@value TransformBypass#HEADER_NAME} header. A request asking for a bypass while this is disabled
     * has already been rejected by {@link PreFilter}; the property is read here as well so that both
     * filters stay independently correct.
     */
    @ConfigProperty(name = "proxy.transform.allow-ignore-header", defaultValue = "true")
    boolean allowIgnoreTransformHeader;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        LOG.infof("POST-FILTER: %s %s",
                requestContext.getMethod(),
                requestContext.getUriInfo().getPath());

        if (responseContext.getStatus() < 200 || responseContext.getStatus() > 299) {
            LOG.warn("Will not transform response since response code is not OK (only 2xx codes are accepted)");
            return;
        }

        // TODO set the proxy string in a application.properties parameter !
        String proxyBasePath = "/proxy";

        String requestPath = requestContext.getUriInfo().getPath();

        // if request is not a sub path of the proxy path
        if (!requestPath.startsWith(proxyBasePath)) {
            LOG.infof("POST-FILTER: Not the proxied path. Skipping filtering.");
            return;
        }

        String proxyUrlPath = requestPath.substring(proxyBasePath.length());

        // the caller may have asked for the payloads to be returned without any RPS transformation
        if (allowIgnoreTransformHeader && TransformBypass.isRequested(requestContext)) {
            LOG.infof("POST filter: %s is set, returning %s > %s without any transformation.",
                    TransformBypass.HEADER_NAME, requestContext.getMethod(), requestPath);
            // echo the header so that the caller can tell the payload was left untransformed
            responseContext.getHeaders().putSingle(TransformBypass.HEADER_NAME, "true");
            return;
        }

        LOG.infof("POST filter: Comparing if we need to transform url path: AFTER: %s > %s", requestContext.getMethod(), proxyUrlPath);
        EndPointTransformConfig cfg = endPointTransformer.getEndpointTransformConfig(requestContext.getMethod(), proxyUrlPath, "AFTER");
        if (cfg != null) {
            LOG.infof("POST filter: Transforming %s > %s", requestContext.getMethod(), requestContext.getUriInfo().getPath());

            try {
                // entity of the response can be transformed
                String responseJson = responseContext.getEntity().toString();

                // headers may also be transformed
                // jakarta inconsistency between request and response headers that should be String in both cases ?
                MultivaluedMap<String, Object> responseHeaders = responseContext.getHeaders();
                MultivaluedMap<String, String> responseHeadersStr = new MultivaluedHashMap<>(responseHeaders.size());
                responseHeaders.keySet().forEach(header -> {
                    List<String> strValues = responseHeaders.get(header).stream().map(Object::toString).toList();
                    responseHeadersStr.put(header, strValues);
                });

                // we can also transform the values of the query parameters, this make little sense here since we should
                // transform the request parameters BEFORE the request is performed by proxy-ied REST api.
                // anyway, this is possible to do so here minimizing the configuration
                MultivaluedMap<String, String> requestParams = requestContext.getUriInfo().getQueryParameters();

                // perform actual transform
                String finalJson = endPointTransformer.transform(responseJson, responseHeadersStr, requestParams, cfg);

                // replacing the transformed values in the response
                responseContext.setEntity(finalJson);

            } catch (RPSTransformException e) {
                LOG.error("POST filter: Cannot transform response to tokenizer.", e);
                requestContext.abortWith(Response.status(500).entity(e.getMessage()).build());
            }
        } else {
            LOG.infof("POST filter: No need to transform: %s > %s", requestContext.getMethod(), requestContext.getUriInfo().getPath());
        }
    }
}
