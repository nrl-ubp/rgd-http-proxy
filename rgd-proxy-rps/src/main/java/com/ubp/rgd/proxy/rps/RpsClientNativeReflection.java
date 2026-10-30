package com.ubp.rgd.proxy.rps;

import ch.regdata.rps.engine.client.model.api.Instance;
import ch.regdata.rps.engine.client.model.api.ValueError;
import ch.regdata.rps.engine.client.model.api.connect.Token;
import ch.regdata.rps.engine.client.model.api.context.Context;
import ch.regdata.rps.engine.client.model.api.context.Evidence;
import ch.regdata.rps.engine.client.model.api.error.Error;
import ch.regdata.rps.engine.client.model.api.request.Request;
import ch.regdata.rps.engine.client.model.api.request.RequestBody;
import ch.regdata.rps.engine.client.model.api.response.Response;
import ch.regdata.rps.engine.client.model.api.response.ResponseBody;
import ch.regdata.rps.engine.client.model.api.value.RPSBigDecimalValue;
import ch.regdata.rps.engine.client.model.api.value.RPSDateValue;
import ch.regdata.rps.engine.client.model.api.value.RPSDoubleValue;
import ch.regdata.rps.engine.client.model.api.value.RPSFloatValue;
import ch.regdata.rps.engine.client.model.api.value.RPSIntValue;
import ch.regdata.rps.engine.client.model.api.value.RPSLongValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValueBase;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native executable: the JSON models of the RegData engine API, which the client library (de)serializes
 * with Jackson, i.e. through reflection. Nothing to do in a JVM.
 */
@RegisterForReflection(targets = {
        Token.class, Context.class, Evidence.class, Error.class, Instance.class, ValueError.class,
        Request.class, RequestBody.class, Response.class, ResponseBody.class,
        RPSValueBase.class, RPSValue.class, RPSBigDecimalValue.class, RPSDateValue.class, RPSDoubleValue.class,
        RPSFloatValue.class, RPSIntValue.class, RPSLongValue.class,
        ch.regdata.rps.engine.client.enginecontext.ProcessingContext.class,
        ch.regdata.rps.engine.client.enginecontext.RightContext.class,
        ch.regdata.rps.engine.client.Evidence.class,
        TokenSecretsManagerResolver.RPSConfigProperty.class})
final class RpsClientNativeReflection {

    private RpsClientNativeReflection() {
    }
}
