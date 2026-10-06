package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.request.RequestBody;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks that {@link RPSEndPointTransformer} hands the secrets manager to the RPS engine request.
 * The engine is a mock: the request body is captured, then the call is failed on purpose.
 */
class RPSEndPointTransformerSecretsManagerTest {

    private static final UUID SECRETS_MANAGER = UUID.fromString("16ec8462-e8d5-4a2c-b8df-f253e09bd274");

    private RPSEndPointTransformer transformer;
    private HttpClientEngineProvider engine;

    @BeforeEach
    void setUp() {
        engine = mock(HttpClientEngineProvider.class);
        when(engine.transformAsync(any())).thenThrow(new IllegalStateException("captured"));
        RPSClientEngineProvider provider = mock(RPSClientEngineProvider.class);
        when(provider.getClientEngineProvider()).thenReturn(engine);

        transformer = new RPSEndPointTransformer();
        transformer.engineProvider = provider;
    }

    @Test
    @DisplayName("The secrets manager is set on the engine request")
    void forwardsTheSecretsManager() {
        assertThrows(IllegalStateException.class, () ->
                transformer.transformData(values(), new Context(), protect(), SECRETS_MANAGER));

        assertEquals(SECRETS_MANAGER, sentRequest().getRequests().getFirst().getSecretsManager());
    }

    @Test
    @DisplayName("The signature without secrets manager lets the engine use its default one")
    void noSecretsManagerByDefault() {
        assertThrows(IllegalStateException.class, () ->
                transformer.transformData(values(), new Context(), protect()));

        assertNull(sentRequest().getRequests().getFirst().getSecretsManager());
    }

    private RequestBody sentRequest() {
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(engine).transformAsync(body.capture());
        return body.getValue();
    }

    private static RPSValue[] values() {
        return new RPSValue[]{new RPSValue(new RPSMapping("Person", "ShortString"), "John")};
    }

    private static ProcessingContext protect() {
        ProcessingContext context = new ProcessingContext();
        context.addEvidence(new Evidence("Action", "Protect"));
        return context;
    }
}
