package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.fpe.FPEEndPointTransformer;
import com.ubp.rgd.proxy.sqlgateway.PhaseContexts;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import com.ubp.rgd.proxy.transform.model.Evidence;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.RightContext;
import com.ubp.rgd.proxy.transform.model.ValueKind;
import com.ubp.rgd.proxy.transform.model.ValueMapping;

import java.util.List;

/** The FPE transformer of the tests: real tokens, without any RPS engine. */
final class Fpe {

    static final FPEEndPointTransformer TRANSFORMER = FPEEndPointTransformer.withKey("000102030405060708090A0B0C0D0E0F");

    private Fpe() {
    }

    static String protect(String clear, String className, String propertyName) throws Exception {
        return protect(clear, className, propertyName, null);
    }

    static String protect(String clear, String className, String propertyName, ValueKind kind) throws Exception {
        MappedValue value = kind == null
                ? new MappedValue(new ValueMapping(className, propertyName), clear)
                : new MappedValue(new ValueMapping(className, propertyName), clear, kind);
        ProcessingContext context = new ProcessingContext();
        context.addEvidence(new Evidence("Action", "Protect"));
        TRANSFORMER.transformData(new MappedValue[]{value}, new RightContext(), context);
        return value.getTransformed();
    }

    static RequestRewriter requestRewriter(MappingConfig config) {
        return new RequestRewriter(new com.ubp.rgd.proxy.sqlgateway.SqlTransformRewriter("TDS gateway"),
                (values, right, processing) -> {
                    try {
                        TRANSFORMER.transformData(values, right, processing);
                    } catch (Exception e) {
                        throw new com.ubp.rgd.proxy.transform.TransformException(e);
                    }
                }, config::getBefore);
    }

    static ResponseProcessor responseProcessor(MappingConfig config, int batchSize, List<Integer> calls) {
        return new ResponseProcessor(TRANSFORMER, () -> config, values -> {
            calls.add(values.size());
            try {
                TRANSFORMER.transformData(values.toArray(new MappedValue[0]), PhaseContexts.rightContext(config.getAfter()),
                        PhaseContexts.processingContext(config.getAfter(), "Unprotect", "TDS gateway 'after'"));
            } catch (Exception e) {
                throw new com.ubp.rgd.proxy.transform.TransformException(e);
            }
        }, batchSize);
    }
}
