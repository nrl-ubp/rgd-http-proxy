package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
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

import java.util.List;

import static com.ubp.rgd.proxy.services.TestSecretsManagers.CH;
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
 * Checks that the service behind {@code /transform} hands the resolved secrets manager to every
 * transformer call, and that the selecting header never reaches
 * the proxied API.
 */
public class SecretsManagerForwardingTest {

    @Test
    @DisplayName("/transform uses the secrets manager for every set")
    void transformServiceForwards() throws Exception {
        EndPointTransformer transformer = echoTransformer();
        TransformService service = new TransformService();
        service.transformer = transformer;
        service.preFilterAuthEnabled = "false";
        service.rightContextTarget = "WDX1";
        service.rightContextModule = "WDX1Proxy";
        service.rightContextRight = "Transform";

        TransformRequest request = new TransformRequest();
        request.setSets(List.of(set("Protect"), set("Protect")));

        service.transform(request, CH);

        verify(transformer, times(2)).transformData(any(), any(), any(), eq(CH));
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

    private static TransformSet set(String action) {
        TransformValue value = new TransformValue();
        value.setValue("John");
        value.setClassName("Person");
        value.setPropertyName("ShortString");
        TransformSet set = new TransformSet();
        set.setAction(action);
        set.setTarget("WDX1");
        set.setJurisdiction("CH");
        set.setValues(List.of(value));
        return set;
    }
}
