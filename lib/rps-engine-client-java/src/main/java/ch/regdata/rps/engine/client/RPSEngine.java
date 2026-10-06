package ch.regdata.rps.engine.client;

import ch.regdata.rps.engine.client.engine.IRPSEngineProvider;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.model.api.request.RequestBody;
import ch.regdata.rps.engine.client.model.api.response.ResponseBody;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;

import java.util.UUID;

public class RPSEngine {

    private final IRPSEngineProvider provider;
    private final RPSEngineConverter converter;
    private final RPSEngineContextResolver engineContextResolver;

    public RPSEngine(IRPSEngineProvider engineProvider,
                     RPSEngineConverter converter,
                     RPSEngineContextResolver contextResolver) {
        this.provider = engineProvider;
        this.converter = converter;
        this.engineContextResolver = contextResolver;
    }

    public RequestContext createContext() {
        return new RequestContext(this, engineContextResolver);
    }


    public void transform(IRPSValue[] rpsValues,
                          Context rightsContext,
                          Context processingContext) {
        transform(rpsValues, rightsContext, processingContext, (Context) null);
    }

    public void transform(IRPSValue[] rpsValues,
                          Context rightsContext,
                          Context processingContext,
                          Context loggingContext) {
        transform(rpsValues, rightsContext, processingContext, loggingContext, null);
    }

    /**
     * @param secretsManager optional secrets manager identifier used for this request (may be null)
     */
    public void transform(IRPSValue[] rpsValues,
                          Context rightsContext,
                          Context processingContext,
                          Context loggingContext,
                          UUID secretsManager) {
        RequestContext requestContext = createContext();
        transform(requestContext.withRequest(rpsValues, rightsContext, processingContext, loggingContext, secretsManager));
    }

    public void transform(RequestContext requestContext) {
        RequestBody requestBody = converter.toRequestBody(requestContext);
        ResponseBody responseBody = provider.transformAsync(requestBody);
        converter.fromResponseBody(responseBody, requestContext);
    }

    public void transform(IRPSValue[] rpsValues, String rightsContextName, String processingContextName, Context loggingContext) throws Exception {
        transform(rpsValues, rightsContextName, processingContextName, loggingContext, null);
    }

    /**
     * @param secretsManager optional secrets manager identifier used for this request (may be null)
     */
    public void transform(IRPSValue[] rpsValues, String rightsContextName, String processingContextName, Context loggingContext,
                          UUID secretsManager) throws Exception {
        if (engineContextResolver == null)
            throw new Exception("Context resolver not found");

        Context rightsContext = engineContextResolver.resolveRightContext(rightsContextName);
        Context processingContext = engineContextResolver.resolveProcessingContext(processingContextName);
        transform(rpsValues, rightsContext, processingContext, loggingContext, secretsManager);
    }

    public void transform(IRPSValue[] rpsValues, String rightsContextName, String processingContextName) throws Exception {
        transform(rpsValues, rightsContextName, processingContextName, (Context) null);
    }
}
