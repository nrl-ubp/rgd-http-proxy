package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.exception.SecretsManagerNotFoundException;
import com.ubp.rgd.proxy.services.TokenSecretsManagerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Checks that {@link RPSEndPointTransformer} unprotects the values of an unknown secrets manager with
 * the one that created each token, in one engine call per secrets manager.
 */
class RPSEndPointTransformerTokenSecretsManagerTest {

    private static final UUID CH = UUID.fromString("16ec8462-e8d5-4a2c-b8df-f253e09bd274");
    private static final UUID LU = UUID.fromString("b9f72aef-6c1b-4556-bf65-9813f122cf8b");

    private static final String CH_TOKEN_1 = "RG{BxAbCd1234Aaa}";
    private static final String CH_TOKEN_2 = "RG{BxAbCd1234Bbb}";
    private static final String LU_TOKEN = "RG{CxEfGh5678Ccc}";

    /** One recorded engine call. */
    private record EngineCall(List<String> values, UUID secretsManager) {
    }

    private final List<EngineCall> calls = new ArrayList<>();
    private TokenSecretsManagerResolver resolver;
    private RPSEndPointTransformer transformer;

    @BeforeEach
    void setUp() {
        resolver = mock(TokenSecretsManagerResolver.class);
        when(resolver.secretsManagerIdResolve(CH_TOKEN_1)).thenReturn(CH);
        when(resolver.secretsManagerIdResolve(CH_TOKEN_2)).thenReturn(CH);
        when(resolver.secretsManagerIdResolve(LU_TOKEN)).thenReturn(LU);

        transformer = new RPSEndPointTransformer() {
            @Override
            protected void callEngine(IRPSValue<String>[] values, Context rightContext,
                                      ProcessingContext processingContext, UUID secretsManager) {
                calls.add(new EngineCall(Arrays.stream(values).map(IRPSValue::getOriginal).toList(), secretsManager));
            }
        };
        transformer.tokenSecretsManagerResolver = resolver;
    }

    @Test
    @DisplayName("Tokens of two secrets managers are unprotected in one call per secrets manager")
    void groupsBySecretsManager() throws Exception {
        transformer.transformData(values(CH_TOKEN_1, LU_TOKEN, CH_TOKEN_2), new Context(), action("Unprotect"), null);

        assertEquals(List.of(
                new EngineCall(List.of(CH_TOKEN_1, CH_TOKEN_2), CH),
                new EngineCall(List.of(LU_TOKEN), LU)), calls);
    }

    @Test
    @DisplayName("A token repeated in the call is resolved once")
    void resolvesEachTokenOnce() throws Exception {
        transformer.transformData(values(CH_TOKEN_1, CH_TOKEN_1, CH_TOKEN_1), new Context(), action("unprotect"), null);

        verify(resolver, times(1)).secretsManagerIdResolve(CH_TOKEN_1);
        assertEquals(List.of(new EngineCall(List.of(CH_TOKEN_1, CH_TOKEN_1, CH_TOKEN_1), CH)), calls);
    }

    @Test
    @DisplayName("An explicit secrets manager is used as is, without resolving the tokens")
    void explicitSecretsManagerWins() throws Exception {
        transformer.transformData(values(CH_TOKEN_1, LU_TOKEN), new Context(), action("Unprotect"), LU);

        verifyNoInteractions(resolver);
        assertEquals(List.of(new EngineCall(List.of(CH_TOKEN_1, LU_TOKEN), LU)), calls);
    }

    @Test
    @DisplayName("Protecting never resolves a secrets manager")
    void protectIsUnchanged() throws Exception {
        transformer.transformData(values("John", CH_TOKEN_1), new Context(), action("Protect"), null);

        verifyNoInteractions(resolver);
        assertEquals(List.of(new EngineCall(List.of("John", CH_TOKEN_1), null)), calls);
    }

    @Test
    @DisplayName("A token that cannot be resolved fails the call before any engine call")
    void unresolvedTokenFailsUpFront() {
        String unknown = "RG{DxIjKl9012Ddd}";
        when(resolver.secretsManagerIdResolve(unknown)).thenThrow(new SecretsManagerNotFoundException("not found"));

        assertThrows(SecretsManagerNotFoundException.class, () ->
                transformer.transformData(values(CH_TOKEN_1, unknown), new Context(), action("Unprotect"), null));

        assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("Values that are not tokens go along with the first secrets manager")
    void nonTokensJoinTheFirstGroup() throws Exception {
        transformer.transformData(values("plain", LU_TOKEN, "", CH_TOKEN_1), new Context(), action("Unprotect"), null);

        assertEquals(List.of(
                new EngineCall(List.of(LU_TOKEN, "plain", ""), LU),
                new EngineCall(List.of(CH_TOKEN_1), CH)), calls);
    }

    @Test
    @DisplayName("Without any token, a single call is made with the default secrets manager")
    void noTokenAtAll() throws Exception {
        transformer.transformData(values("plain", "RG{short}"), new Context(), action("Unprotect"), null);

        verifyNoInteractions(resolver);
        assertEquals(1, calls.size());
        assertNull(calls.getFirst().secretsManager());
        assertEquals(List.of("plain", "RG{short}"), calls.getFirst().values());
    }

    private static RPSValue[] values(String... originals) {
        return Arrays.stream(originals)
                .map(value -> new RPSValue(new RPSMapping("Person", "ShortString"), value))
                .toArray(RPSValue[]::new);
    }

    private static ProcessingContext action(String action) {
        ProcessingContext context = new ProcessingContext();
        context.addEvidence(new Evidence("Action", action));
        return context;
    }
}
