package com.ubp.rgd.proxy.queue;

import com.ubp.rgd.proxy.fpe.FPEEndPointTransformer;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.TransformException;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.RightContext;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The FPE transformer, which can be told to fail the next transformations of the payloads matching a
 * predicate, the way an unreachable engine would.
 */
final class ScriptedTransformer implements EndPointTransformer {

    static final String KEY = "000102030405060708090A0B0C0D0E0F";

    private final FPEEndPointTransformer fpe = FPEEndPointTransformer.withKey(KEY);
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile Predicate<String> failing = payload -> false;
    private volatile Exception failure;
    final AtomicInteger calls = new AtomicInteger();

    /** Fails the next {@code times} transformations of the payloads matching {@code payloads}. */
    void failNext(int times, Predicate<String> payloads, Exception failure) {
        this.failing = payloads;
        this.failure = failure;
        failuresLeft.set(times);
    }

    @Override
    public String transformJson(String json, Map<String, EntityTransformConfig> attributeConfigs,
                                Map<String, String> rightContextEvidences,
                                Map<String, String> processingContextEvidences,
                                UUID secretsManager) throws TransformException {
        calls.incrementAndGet();
        if (failing.test(json) && failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new TransformException(failure);
        }
        return fpe.transformJson(json, attributeConfigs, rightContextEvidences, processingContextEvidences, secretsManager);
    }

    @Override
    public void transformData(MappedValue[] values, RightContext rightContext, ProcessingContext processingContext,
                              UUID secretsManager) throws Exception {
        fpe.transformData(values, rightContext, processingContext, secretsManager);
    }

    @Override
    public EndPointTransformConfig getEndpointTransformConfig(String apiMethod, String apiPath, String when) {
        return fpe.getEndpointTransformConfig(apiMethod, apiPath, when);
    }

    @Override
    public String transform(String json, MultivaluedMap<String, String> headers,
                            MultivaluedMap<String, String> queryParameters, EndPointTransformConfig cfg,
                            UUID secretsManager) throws TransformException {
        return fpe.transform(json, headers, queryParameters, cfg, secretsManager);
    }

    @Override
    public Pattern tokenPattern() {
        return fpe.tokenPattern();
    }

    @Override
    public boolean supportsTokenMappingIndex() {
        return fpe.supportsTokenMappingIndex();
    }
}
