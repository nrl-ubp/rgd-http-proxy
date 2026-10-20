package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.exception.UnknownSecretsManagerException;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.api.TransformRequest;
import com.ubp.rgd.proxy.transform.api.TransformSet;
import com.ubp.rgd.proxy.transform.api.TransformValue;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.List;

import static com.ubp.rgd.proxy.services.TestSecretsManagers.CH;
import static com.ubp.rgd.proxy.services.TestSecretsManagers.LU;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks that the service behind {@code /transform} transforms each set with the secrets manager
 * of its jurisdiction, and that the selecting header never reaches the proxied API.
 */
public class SecretsManagerForwardingTest {

    @Test
    @DisplayName("/transform uses the secrets manager of each set's jurisdiction, the default one without")
    void transformServiceUsesEachSetJurisdiction() throws Exception {
        EndPointTransformer transformer = echoTransformer();
        TransformService service = transformService(transformer);

        service.transform(request(set("CH"), set("lu"), set(null), set(" ")));

        InOrder inOrder = inOrder(transformer);
        inOrder.verify(transformer).transformData(any(), any(), any(), eq(CH));
        inOrder.verify(transformer, times(3)).transformData(any(), any(), any(), eq(LU));
        verifyNoMoreInteractions(transformer);
    }

    @Test
    @DisplayName("/transform rejects an unknown jurisdiction before transforming any set")
    void transformServiceRejectsUnknownJurisdictionUpFront() throws Exception {
        EndPointTransformer transformer = echoTransformer();
        TransformService service = transformService(transformer);

        UnknownSecretsManagerException e = assertThrows(UnknownSecretsManagerException.class,
                () -> service.transform(request(set("CH"), set("MC"))));

        assertEquals(400, e.getResponse().getStatus());
        assertEquals("Unknown jurisdiction of set #2 value: MC", e.getMessage());
        verifyNoInteractions(transformer);
    }

    private static TransformService transformService(EndPointTransformer transformer) {
        TransformService service = new TransformService();
        service.transformer = transformer;
        service.secretsManagerResolver = TestSecretsManagers.resolver();
        service.preFilterAuthEnabled = "false";
        service.rightContextTarget = "WDX1";
        service.rightContextModule = "WDX1Proxy";
        service.rightContextRight = "Transform";
        return service;
    }

    private static TransformRequest request(TransformSet... sets) {
        TransformRequest request = new TransformRequest();
        request.setSets(Arrays.asList(sets));
        return request;
    }

    @Test
    @DisplayName("The secrets manager header is not forwarded to the proxied API")
    void proxyServiceStripsTheHeader() {
        ProxyService proxyService = new ProxyService();
        proxyService.secretsManagerHeader = TestSecretsManagers.HEADER;

        MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.add("x-proxy-jurisdiction", "CH");
        requestHeaders.add("Accept", "application/json");
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);
        Invocation.Builder builder = mock(Invocation.Builder.class);

        proxyService.copyHeaders(headers, builder);

        verify(builder).header("Accept", "application/json");
        verify(builder, never()).header(eq("x-proxy-jurisdiction"), anyString());
    }

    /** A transformer mock that hands every value back as transformed, unchanged. */
    @SuppressWarnings("unchecked")
    public static EndPointTransformer echoTransformer() throws Exception {
        EndPointTransformer transformer = mock(EndPointTransformer.class);
        doAnswer(invocation -> {
            for (IRPSValue<String> value : (IRPSValue<String>[]) invocation.getArgument(0)) {
                String original = value.getOriginal();
                ((RPSValue) value).setTransformed(original.startsWith("RG{") ? "1917-05-29" : original);
            }
            return null;
        }).when(transformer).transformData(any(), any(), any(), any());
        return transformer;
    }

    private static TransformSet set(String jurisdiction) {
        TransformValue value = new TransformValue();
        value.setValue("John");
        value.setClassName("Person");
        value.setPropertyName("ShortString");
        TransformSet set = new TransformSet();
        set.setAction("Protect");
        set.setTarget("WDX1");
        set.setJurisdiction(jurisdiction);
        set.setValues(List.of(value));
        return set;
    }
}
