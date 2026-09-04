package com.ubp.rgd.proxy.filters;

import com.ubp.rgd.proxy.security.BasicToken;
import com.ubp.rgd.proxy.security.KerberosToken;
import com.ubp.rgd.proxy.security.SecurityContext;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.RPSEndPointTransformer;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PreFilter is authenticating users against the proxy service.
 * Only authenticated users can use the proxy service.
 * If no authentication and users not in the authorized list are receiving a 403 response status code.
 * Secondly, the pre filter will transform payload of the request agains RegData RPS platform depending on the method
 * and endpoint path (target url)
 */
@Provider
public class PreFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(PreFilter.class);

    @ConfigProperty(name = "proxy.prefilter.auth-enabled", defaultValue = "true")
    String preFilterAuthEnabled;

    /**
     * Whether a caller is allowed to skip the RPS transformation with the
     * {@value TransformBypass#HEADER_NAME} header.
     */
    @ConfigProperty(name = "proxy.transform.allow-ignore-header", defaultValue = "true")
    boolean allowIgnoreTransformHeader;

    @Inject
    SecurityContext securityContext;

    /**
     * Kerberos token decoder and validator bean
     */
    @Inject
    KerberosToken krbToken;

    /**
     * Basic token decoder.
     */
    @Inject
    BasicToken basicToken;

    /**
     * Endpoint transformer bean to transform payload depending of the request method and path
     */
    @Inject
    RPSEndPointTransformer endpointTransformer;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // add a timestamp to request to ease debugging
        requestContext.setProperty("request.timestamp", LocalDateTime.now());

        String requestPath = requestContext.getUriInfo().getPath();

        // trace request
        LOG.infof("PRE-FILTER: %s %s du client %s",
                requestContext.getMethod(),
                requestPath,
                getClientIp(requestContext));

        // AuthN prefilter : always initialize the security context regardless of the API called
        if (!"true".equalsIgnoreCase(preFilterAuthEnabled)) {
            LOG.warn("Pre Filter AUTH is disabled by configuration. See proxy.prefilter.auth-enabled property in config file.");
        } else {
            handleAuthForRequest(requestContext);
        }

        // add personalized header to ease debug
        requestContext.getHeaders().add("X-Proxy-Processed", "true");
        requestContext.getHeaders().add("X-Proxy-Timestamp", LocalDateTime.now().toString());

        // if request is not a sub path of the proxy path then do nothing
        if (!requestPath.startsWith("/proxy")) {
            LOG.infof("PRE-FILTER: Not the proxied path. Skipping filtering.");
            return;
        }

        // Personalized validation, eg authZ or any business related validation can occur in this method
        validateRequest(requestContext);

        // a caller may ask for the payloads to be forwarded without any RPS transformation
        if (!allowIgnoreTransformHeader && TransformBypass.isPresent(requestContext)) {
            LOG.warnf("PRE-FILTER: %s was sent while the transform bypass is disabled by configuration."
                    + " See the proxy.transform.allow-ignore-header property.", TransformBypass.HEADER_NAME);
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity(String.format("The %s header is not allowed on this proxy.",
                            TransformBypass.HEADER_NAME))
                    .build());
            return;
        }

        if (TransformBypass.isRequested(requestContext)) {
            LOG.infof("PRE-FILTER: %s is set, forwarding %s > %s without any transformation.",
                    TransformBypass.HEADER_NAME, requestContext.getMethod(), requestPath);
            return;
        }

        // now check if we should transform payload BEFORE invoking proxified target url
        String proxyUrlPath = requestContext.getUriInfo().getPath().substring("/proxy".length());
        LOG.infof("Comparing if we need to transform url path: BEFORE: %s > %s", requestContext.getMethod(), proxyUrlPath);
        EndPointTransformConfig cfg = endpointTransformer.getEndpointTransformConfig(requestContext.getMethod(), proxyUrlPath, "BEFORE");
        if (cfg != null) {
            LOG.infof("PRE filter: Transforming %s > %s", requestContext.getMethod(), requestContext.getUriInfo().getPath());

            try {
                InputStream is = requestContext.getEntityStream();
                String originalBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                // TODO can we have a pre filter without transforming the body?
                if (originalBody.isEmpty()) {
                    throw new RPSTransformException(String.format("Cannot transform BEFORE: %s : %s. Request's body is empty.", requestContext.getMethod(), proxyUrlPath));
                }

                // request headers to transform
                MultivaluedMap<String, String> requestHeaders =  requestContext.getHeaders();

                // we can also transform the values of the query parameters
                MultivaluedMap<String, String> requestParams = requestContext.getUriInfo().getQueryParameters();

                // perform actual transformation
                String finalJson = endpointTransformer.transform(originalBody, requestHeaders, requestParams, cfg);

                InputStream modifiedInputStream = new ByteArrayInputStream(finalJson.getBytes(StandardCharsets.UTF_8));
                requestContext.setEntityStream(modifiedInputStream);
            } catch (RPSTransformException e) {
                LOG.error("PRE FILTER: Cannot transform response to tokenizer.", e);
                requestContext.abortWith(Response.status(500).entity(e.getMessage()).build());
            }
        } else {
            LOG.infof("No need to transform: %s > %s", requestContext.getMethod(), requestContext.getUriInfo().getPath());
        }
    }

    /**
     * Use Basic auth for client and server on the same server (aka localhost), or Kerberos
     * if running on a server (client is on another machine)
     * @param requestContext the ContainerRequestContext of this request.
     * @see #handleBasicAuthForRequest(ContainerRequestContext)
     * @see #handleBasicAuthForRequest(ContainerRequestContext)
     */
    private void handleAuthForRequest(ContainerRequestContext requestContext) {
        if (isLocalRequest(requestContext)) {
            handleBasicAuthForRequest(requestContext);
        } else {
            handleKerberosAuthForRequest(requestContext);
        }
    }

    /**
     * Performs a Basic auth against Kerberos. Use the Krb5LoginModule to login the user with username and password
     * The context abort in case of incorrect credentials
     * @param requestContext the container request context to get Authorization header
     * @see #handleAuthForRequest(ContainerRequestContext)
     */
    private void handleBasicAuthForRequest(ContainerRequestContext requestContext) {
        LOG.infof("PRE-FILTER: BASIC auth %s %s du client %s",
                requestContext.getMethod(),
                requestContext.getUriInfo().getPath(),
                getClientIp(requestContext));

        List<String> authValues = requestContext.getHeaders().get("Authorization");
        if (authValues == null) {
            // failed authentication, try to negotiate with caller
            Response basicNegoResp = getBasicNegociateResponse();
            requestContext.abortWith(basicNegoResp);
            return;
        }

        try {
            String authHeader = authValues.getFirst();
            basicToken.decode(authHeader);

            if (basicToken.getUser() != null) {
                securityContext.setToken(basicToken);
                // Retain the authenticated user's Subject (holding the TGT) for the request so that
                // downstream forwarding can obtain a client-to-service ticket as this user.
                securityContext.setUserSubject(basicToken.getUserSubject());
            } else {
                // too bad, clear security context
                securityContext.setToken(null);
                // and try again!
                Response basicNegoResp = getBasicNegociateResponse();
                requestContext.abortWith(basicNegoResp);
            }
        } catch (Exception ex) {
            LOG.error("Cannot compute the security context in PRE filter.", ex);
            Response respForbidden = Response.status(Response.Status.FORBIDDEN).entity("Invalid BASIC credentials.").build();
            requestContext.abortWith(respForbidden);
        }
    }

    /**
     * Build and send an Unauthorized response with basic auth scheme and ream CORP.UBP.CH
     * @return Response to send for an negotiate basic scheme
     */
    private Response getBasicNegociateResponse() {
        Response.ResponseBuilder builder = Response.status(Response.Status.UNAUTHORIZED);
        try {
            builder.header("WWW-Authenticate", "Basic realm=\"CORP.UBP.CH\"");
            builder.entity("Must provide auth token to use this service.");
        } catch (Exception ex) {
            builder = Response.serverError().entity("Cannot build auth headers for auth token exchange.");
        }

        return builder.build();
    }

    /**
     * Performs authentication against kerberos using the ticket from the Authorization header
     * @param requestContext the container request containing http request headers
     * @see KerberosToken for more details on implementation
     */
    private void handleKerberosAuthForRequest(ContainerRequestContext requestContext) {
        LOG.infof("PRE-FILTER: KERBEROS auth %s %s of client %s",
                requestContext.getMethod(),
                requestContext.getUriInfo().getPath(),
                getClientIp(requestContext));

        // we ask the client for auth only if there is no Authorization header in the HTTP request.
        List<String> authValues = requestContext.getHeaders().get("Authorization");
        if (authValues == null || authValues.isEmpty()) {
            Response respNegoKerb =  getKerberosNegociateResponse(null);
            requestContext.abortWith(respNegoKerb);
            return;
        }

        // we have authorization header with some kerberos ticket to validate.
        // step 1 : validate the ticket from client  then send back our ticket
        // step 2 : get the logged in user from the client new ticket and populated this info into the request object
        try {
            String base64Ticket = authValues.getFirst();
            krbToken.decode(base64Ticket);

            if (krbToken.getToken() != null) {
                // valid user, set the context into the request
                securityContext.setToken(krbToken);
                securityContext.setUserSubject(krbToken.getUserSubject());
                LOG.debugf("User %s has %s roles configured.", krbToken.getUser(), krbToken.getRoles().size());
            } else {
                Response respNegoKerb = getKerberosNegociateResponse(krbToken.getServiceToken());
                requestContext.abortWith(respNegoKerb);
            }
        } catch (Exception ex) {
            LOG.error("Cannot validate ticket for authorization.", ex);
            Response errorResp = Response.serverError().entity(String.format("Cannot validate ticket for authorization: %s", ex.getMessage())).build();
            requestContext.abortWith(errorResp);
        }
    }

    /**
     * Build a HttpResponse with Unauthorized status code and asking negociation
     * through the WWW-Authenticate header.
     * @return HttpResponse to send back to client.
     */
    private Response getKerberosNegociateResponse(byte[] acceptedToken) {
        Response.ResponseBuilder builder = Response.status(Response.Status.UNAUTHORIZED);
        try {
            String header = "Negotiate";
            if (acceptedToken != null) {
                String b64Token = new String(java.util.Base64.getEncoder().encode(acceptedToken));
                header = String.format("%s %s", header, b64Token);
            }

            LOG.debugf("Adding header: WWW-Authenticate: %s", header);
            builder.header("WWW-Authenticate", header)
                    .entity( "Must provide auth token to use this service.");
        } catch (Exception ex) {
            builder = Response.serverError().entity("Cannot build auth headers for auth token exchange.");
        }

        return builder.build();
    }

    /**
     * Return true if the ip parameter is 127.0.0.1 or localhost
     * @param ip the IP to test
     * @return true if localhost and false otherwise
     */
    private boolean isLocalHost(String ip) {
        return "127.0.0.1".equals(ip) || "localhost".equalsIgnoreCase((ip));
    }

    /**
     * This metho is used to determine if the client is on the same machine (using localhost in the request)
     * than the server. Used to decide if we use basic or kerberos ticket authentication
     * @param ctx Container Request Context containng request http headers.
     * @return true is the client is using the localhost or 127.0.0.1 ip to connect to the server.
     */
    private boolean isLocalRequest(ContainerRequestContext ctx) {
        String host = ctx.getUriInfo().getRequestUri().getHost();
        return isLocalHost(host);
    }

    /**
     * Analyzes the X-Forward-For X-Real-IP headers that certain network equipments add when forwarding requests.
     * @param requestContext the container request context that should contain the added headers
     * @return client ip if contained in one of the headers above and unknown if not found in the headers
     */
    private String getClientIp(ContainerRequestContext requestContext) {
        // Vérifier les headers de proxy
        String xForwardedFor = requestContext.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = requestContext.getHeaderString("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return "unknown";
    }

    /**
     * Sample method to validate the request, for instance filtering user agents or validate the actual content length
     * is coherent with the request. if anything goes wrong then the request context is aborted.
     * @param requestContext the container request context to validate
     */
    private void validateRequest(ContainerRequestContext requestContext) {
        // Validation de sécurité basique
        String userAgent = requestContext.getHeaderString("User-Agent");
        if (userAgent != null && userAgent.toLowerCase().contains("bot")) {
            LOG.warn("Requête suspecte détectée (bot): " + userAgent);
            // Optionnel : bloquer les bots
            // requestContext.abortWith(Response.status(Response.Status.FORBIDDEN).build());
        }

        // Validation de la taille du contenu
        String contentLength = requestContext.getHeaderString("Content-Length");
        if (contentLength != null) {
            try {
                long length = Long.parseLong(contentLength);
                if (length > 10_000_000) { // 10MB max
                    LOG.warn("Request with too large body contents: " + length + " bytes");
                    requestContext.abortWith(
                            Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                                    .entity("Too large entity size")
                                    .build()
                    );
                }
            } catch (NumberFormatException e) {
                LOG.warn("Content-Length is invalid: " + contentLength);
            }
        }
    }
}