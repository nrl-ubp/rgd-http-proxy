package com.ubp.rgd.proxy.filters;

import com.ubp.rgd.proxy.services.SecretsManagerResolver;
import com.ubp.rgd.proxy.services.TestSecretsManagers;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static com.ubp.rgd.proxy.services.TestSecretsManagers.CH;
import static com.ubp.rgd.proxy.services.TestSecretsManagers.HEADER;
import static com.ubp.rgd.proxy.services.TestSecretsManagers.LU;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers how {@link PreFilter} and {@link PostFilter} select the RPS secrets manager from the
 * {@value TestSecretsManagers#HEADER} header.
 */
class SecretsManagerFilterTest {

    private EndPointTransformer transformer;
    private PreFilter preFilter;
    private PostFilter postFilter;
    private ContainerRequestContext requestContext;
    private ContainerResponseContext responseContext;

    @BeforeEach
    void setUp() throws Exception {
        transformer = mock(EndPointTransformer.class);
        when(transformer.transform(anyString(), any(), any(), any(), any())).thenReturn("{}");

        preFilter = new PreFilter();
        preFilter.endpointTransformer = transformer;
        preFilter.allowIgnoreTransformHeader = true;
        preFilter.preFilterAuthEnabled = "false";
        preFilter.secretsManagerResolver = TestSecretsManagers.resolver();

        postFilter = new PostFilter();
        postFilter.endPointTransformer = transformer;
        postFilter.allowIgnoreTransformHeader = true;
        postFilter.secretsManagerResolver = TestSecretsManagers.resolver();

        requestContext = mock(ContainerRequestContext.class);
        when(requestContext.getMethod()).thenReturn("POST");
        when(requestContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(requestContext.getEntityStream())
                .thenReturn(new ByteArrayInputStream("{\"name\":\"John\"}".getBytes(StandardCharsets.UTF_8)));
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("/proxy/persons");
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(requestContext.getUriInfo()).thenReturn(uriInfo);

        responseContext = mock(ContainerResponseContext.class);
        when(responseContext.getStatus()).thenReturn(200);
        when(responseContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(responseContext.getEntity()).thenReturn("{\"name\":\"RG{x}\"}");
    }

    @Test
    @DisplayName("The request is transformed with the secrets manager named by the header")
    void preFilterForwardsTheMappedSecretsManager() throws Exception {
        when(requestContext.getHeaderString(HEADER)).thenReturn("ch");
        when(transformer.getEndpointTransformConfig("POST", "/persons", "BEFORE"))
                .thenReturn(new EndPointTransformConfig());

        preFilter.filter(requestContext);

        verify(transformer).transform(anyString(), any(), any(), any(), eq(CH));
        verify(requestContext).setProperty(SecretsManagerResolver.REQUEST_PROPERTY, CH);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    @DisplayName("Without the header the default mapping is used")
    void preFilterUsesTheDefault() throws Exception {
        when(transformer.getEndpointTransformConfig("POST", "/persons", "BEFORE"))
                .thenReturn(new EndPointTransformConfig());

        preFilter.filter(requestContext);

        verify(transformer).transform(anyString(), any(), any(), any(), eq(LU));
    }

    @Test
    @DisplayName("An unknown value is rejected with a 400 even when nothing is transformed BEFORE")
    void preFilterRejectsAnUnknownValue() throws Exception {
        when(requestContext.getHeaderString(HEADER)).thenReturn("MC");
        when(transformer.getEndpointTransformConfig(anyString(), anyString(), anyString())).thenReturn(null);

        preFilter.filter(requestContext);

        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(response.capture());
        assertEquals(400, response.getValue().getStatus());
        verify(transformer, never()).transform(anyString(), any(), any(), any(), any());
        verify(transformer, never()).getEndpointTransformConfig(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A bypassed request does not need a known secrets manager")
    void preFilterBypassSkipsTheResolution() throws Exception {
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");
        when(requestContext.getHeaderString(HEADER)).thenReturn("MC");

        preFilter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
        verify(requestContext, never()).setProperty(eq(SecretsManagerResolver.REQUEST_PROPERTY), any());
    }

    @Test
    @DisplayName("A non proxied path ignores the header")
    void preFilterIgnoresNonProxiedPaths() throws Exception {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("/transform");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(requestContext.getHeaderString(HEADER)).thenReturn("MC");

        preFilter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    @DisplayName("The response is transformed with the secrets manager the PreFilter resolved")
    void postFilterReusesThePreFilterResolution() throws Exception {
        when(requestContext.getMethod()).thenReturn("GET");
        when(requestContext.getProperty(SecretsManagerResolver.REQUEST_PROPERTY)).thenReturn(CH);
        when(transformer.getEndpointTransformConfig("GET", "/persons", "AFTER"))
                .thenReturn(new EndPointTransformConfig());

        postFilter.filter(requestContext, responseContext);

        verify(transformer).transform(anyString(), any(), any(), any(), eq(CH));
        verify(responseContext).setEntity("{}");
    }

    @Test
    @DisplayName("The PostFilter resolves the header itself when the PreFilter did not")
    void postFilterResolvesWhenNeeded() throws Exception {
        when(requestContext.getMethod()).thenReturn("GET");
        when(requestContext.getHeaderString(HEADER)).thenReturn("CH");
        when(transformer.getEndpointTransformConfig("GET", "/persons", "AFTER"))
                .thenReturn(new EndPointTransformConfig());

        postFilter.filter(requestContext, responseContext);

        verify(transformer).transform(anyString(), any(), any(), any(), eq(CH));
    }

    @Test
    @DisplayName("The PostFilter answers 400 rather than transform with an unknown secrets manager")
    void postFilterRejectsAnUnknownValue() throws Exception {
        when(requestContext.getMethod()).thenReturn("GET");
        when(requestContext.getHeaderString(HEADER)).thenReturn("MC");
        when(transformer.getEndpointTransformConfig("GET", "/persons", "AFTER"))
                .thenReturn(new EndPointTransformConfig());

        postFilter.filter(requestContext, responseContext);

        verify(responseContext).setStatus(400);
        verify(transformer, never()).transform(anyString(), any(), any(), any(), any());
    }
}
