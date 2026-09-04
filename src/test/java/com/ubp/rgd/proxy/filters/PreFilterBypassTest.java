package com.ubp.rgd.proxy.filters;

import com.ubp.rgd.proxy.transform.RPSEndPointTransformer;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers how {@link PreFilter} honours the {@value TransformBypass#HEADER_NAME} header. The
 * authentication is switched off through {@code proxy.prefilter.auth-enabled}, so that these tests
 * exercise the transformation path only.
 */
class PreFilterBypassTest {

    private PreFilter filter;
    private RPSEndPointTransformer transformer;
    private ContainerRequestContext requestContext;

    @BeforeEach
    void setUp() {
        transformer = mock(RPSEndPointTransformer.class);
        filter = new PreFilter();
        filter.endpointTransformer = transformer;
        filter.allowIgnoreTransformHeader = true;
        // Authentication is covered elsewhere and needs a live KDC: keep it out of these tests.
        filter.preFilterAuthEnabled = "false";

        requestContext = mock(ContainerRequestContext.class);
        when(requestContext.getMethod()).thenReturn("POST");
        when(requestContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(requestContext.getEntityStream()).thenReturn(bodyStream());

        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("/proxy/persons");
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        when(uriInfo.getQueryParameters()).thenReturn(params);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
    }

    @Test
    void shouldNotTransformWhenTheBypassIsRequested() throws Exception {
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");

        filter.filter(requestContext);

        verify(transformer, never()).getEndpointTransformConfig(anyString(), anyString(), anyString());
        verify(requestContext, never()).setEntityStream(any());
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void shouldNotTransformWhateverTheCaseOfTheValue() throws Exception {
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("TrUe");

        filter.filter(requestContext);

        verify(transformer, never()).getEndpointTransformConfig(anyString(), anyString(), anyString());
    }

    @Test
    void shouldTransformAsUsualWithoutTheHeader() throws Exception {
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn(null);
        EndPointTransformConfig config = new EndPointTransformConfig();
        when(transformer.getEndpointTransformConfig("POST", "/persons", "BEFORE")).thenReturn(config);
        when(transformer.transform(anyString(), any(), any(), any())).thenReturn("{\"name\":\"RG{x}\"}");

        filter.filter(requestContext);

        ArgumentCaptor<InputStream> stream = ArgumentCaptor.forClass(InputStream.class);
        verify(requestContext).setEntityStream(stream.capture());
        assertEquals("{\"name\":\"RG{x}\"}",
                new String(stream.getValue().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void shouldTransformAsUsualOnANonEnablingValue() throws Exception {
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("false");
        EndPointTransformConfig config = new EndPointTransformConfig();
        when(transformer.getEndpointTransformConfig("POST", "/persons", "BEFORE")).thenReturn(config);
        when(transformer.transform(anyString(), any(), any(), any())).thenReturn("{\"name\":\"RG{x}\"}");

        filter.filter(requestContext);

        verify(requestContext).setEntityStream(any());
    }

    @Test
    void shouldRejectTheRequestWhenTheBypassIsDisabledByConfiguration() throws Exception {
        filter.allowIgnoreTransformHeader = false;
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");

        filter.filter(requestContext);

        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(response.capture());
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getValue().getStatus());
        verify(transformer, never()).getEndpointTransformConfig(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRejectTheRequestWhateverTheValueOfTheHeaderWhenItIsDisabled() throws Exception {
        filter.allowIgnoreTransformHeader = false;
        // The caller asked for a bypass at all: refuse loudly rather than transform silently.
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("false");

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    @Test
    void shouldNotRejectARequestWithoutTheHeaderWhenItIsDisabled() throws Exception {
        filter.allowIgnoreTransformHeader = false;
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn(null);
        when(transformer.getEndpointTransformConfig(anyString(), anyString(), anyString()))
                .thenReturn(null);

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void shouldNotFailOnAnEmptyBodyWhenTheBypassIsRequested() throws Exception {
        // Without the bypass an empty body raises an RPSTransformException and aborts with a 500.
        when(requestContext.getEntityStream())
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        when(transformer.getEndpointTransformConfig("POST", "/persons", "BEFORE"))
                .thenReturn(new EndPointTransformConfig());
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void shouldIgnoreTheHeaderOnANonProxiedPath() throws Exception {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("/health");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
        verify(transformer, never()).getEndpointTransformConfig(anyString(), anyString(), anyString());
    }

    @Test
    void shouldAddTheProxyDebugHeadersWhateverTheBypass() throws Exception {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        when(requestContext.getHeaders()).thenReturn(headers);
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn("true");

        filter.filter(requestContext);

        assertTrue(headers.containsKey("X-Proxy-Processed"));
    }

    private static InputStream bodyStream() {
        return new ByteArrayInputStream("{\"name\":\"John\"}".getBytes(StandardCharsets.UTF_8));
    }
}
