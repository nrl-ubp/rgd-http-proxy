package com.ubp.rgd.proxy.filters;

import com.ubp.rgd.proxy.transform.RPSEndPointTransformer;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers how {@link PostFilter} honours the {@value TransformBypass#HEADER_NAME} header. The
 * authentication of {@link PreFilter} is out of reach of a plain unit test, so the pre-filter side of
 * the bypass is covered by {@link PreFilterBypassTest}.
 */
class PostFilterBypassTest {

    private PostFilter filter;
    private RPSEndPointTransformer transformer;
    private ContainerRequestContext requestContext;
    private ContainerResponseContext responseContext;
    private MultivaluedMap<String, Object> responseHeaders;

    @BeforeEach
    void setUp() {
        transformer = mock(RPSEndPointTransformer.class);
        filter = new PostFilter();
        filter.endPointTransformer = transformer;
        filter.allowIgnoreTransformHeader = true;

        requestContext = mock(ContainerRequestContext.class);
        when(requestContext.getMethod()).thenReturn("GET");
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("/proxy/persons");
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(requestContext.getUriInfo()).thenReturn(uriInfo);

        responseHeaders = new MultivaluedHashMap<>();
        responseContext = mock(ContainerResponseContext.class);
        when(responseContext.getStatus()).thenReturn(200);
        when(responseContext.getHeaders()).thenReturn(responseHeaders);
        when(responseContext.getEntity()).thenReturn("{\"name\":\"RG{AB12345678aa}\"}");
    }

    @Test
    void shouldNotTransformWhenTheBypassIsRequested() {
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");

        filter.filter(requestContext, responseContext);

        verify(transformer, never()).getEndpointTransformConfig(anyString(), anyString(), anyString());
        verify(responseContext, never()).setEntity(any());
    }

    @Test
    void shouldEchoTheHeaderWhenTheBypassIsHonoured() {
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("TRUE");

        filter.filter(requestContext, responseContext);

        assertEquals("true", responseHeaders.getFirst(TransformBypass.HEADER_NAME));
    }

    @Test
    void shouldEchoTheHeaderEvenWhenNoTransformationWasConfigured() {
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");
        when(transformer.getEndpointTransformConfig(anyString(), anyString(), anyString()))
                .thenReturn(null);

        filter.filter(requestContext, responseContext);

        assertEquals("true", responseHeaders.getFirst(TransformBypass.HEADER_NAME));
    }

    @Test
    void shouldTransformAsUsualWithoutTheHeader() throws Exception {
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn(null);
        EndPointTransformConfig config = new EndPointTransformConfig();
        when(transformer.getEndpointTransformConfig("GET", "/persons", "AFTER")).thenReturn(config);
        when(transformer.transform(anyString(), any(), any(), any())).thenReturn("{\"name\":\"John\"}");

        filter.filter(requestContext, responseContext);

        ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
        verify(responseContext).setEntity(entity.capture());
        assertEquals("{\"name\":\"John\"}", entity.getValue());
        assertNull(responseHeaders.getFirst(TransformBypass.HEADER_NAME));
    }

    @Test
    void shouldTransformAsUsualOnANonEnablingValue() throws Exception {
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("false");
        EndPointTransformConfig config = new EndPointTransformConfig();
        when(transformer.getEndpointTransformConfig("GET", "/persons", "AFTER")).thenReturn(config);
        when(transformer.transform(anyString(), any(), any(), any())).thenReturn("{\"name\":\"John\"}");

        filter.filter(requestContext, responseContext);

        verify(responseContext).setEntity("{\"name\":\"John\"}");
        assertNull(responseHeaders.getFirst(TransformBypass.HEADER_NAME));
    }

    @Test
    void shouldNotHonourTheBypassWhenItIsDisabledByConfiguration() throws Exception {
        filter.allowIgnoreTransformHeader = false;
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");
        EndPointTransformConfig config = new EndPointTransformConfig();
        when(transformer.getEndpointTransformConfig("GET", "/persons", "AFTER")).thenReturn(config);
        when(transformer.transform(anyString(), any(), any(), any())).thenReturn("{\"name\":\"John\"}");

        filter.filter(requestContext, responseContext);

        verify(responseContext).setEntity("{\"name\":\"John\"}");
        assertNull(responseHeaders.getFirst(TransformBypass.HEADER_NAME));
    }

    @Test
    void shouldNotEchoTheHeaderOnANonProxiedPath() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("/health");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");

        filter.filter(requestContext, responseContext);

        assertNull(responseHeaders.getFirst(TransformBypass.HEADER_NAME));
    }

    @Test
    void shouldNotEchoTheHeaderOnAFailedResponse() {
        when(responseContext.getStatus()).thenReturn(Response.Status.BAD_REQUEST.getStatusCode());
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");

        filter.filter(requestContext, responseContext);

        assertNull(responseHeaders.getFirst(TransformBypass.HEADER_NAME));
    }
}
