package ch.regdata.rps.engine.client;

import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;

import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RequestContext class used to combine several Requests into single call
 */
public class RequestContext {

    private final ConcurrentHashMap<UUID, Request> requestsByGuid = new ConcurrentHashMap<UUID, Request>();
    private final RPSEngine engine;
    private final RPSEngineContextResolver engineContextResolver;

    public RequestContext(RPSEngine engine, RPSEngineContextResolver contextResolver) {
        this.engine = engine;
        this.engineContextResolver = contextResolver;
    }

    public Enumeration<Request> getRequests() {
        return Collections.enumeration(requestsByGuid.values());
    }

    public Request tryGetRequest(UUID uuid) {
        return requestsByGuid.get(uuid);
    }

    public void transform() throws Exception {

        if (engine == null)
            throw new Exception("Engine not found");

        engine.transform(this);
    }

    public RequestContext withRequest(IRPSValue[] rpsValues,
                                      String rightsContextName,
                                      String processingContextName) throws Exception {
        return withRequest(rpsValues, rightsContextName, processingContextName, (Context) null);
    }

    public RequestContext withRequest(IRPSValue[] rpsValues,
                                      String rightsContextName,
                                      String processingContextName,
                                      Context loggingContext) throws Exception {
        return withRequest(rpsValues, rightsContextName, processingContextName, loggingContext, null);
    }

    /**
     * Adds a request whose contexts are resolved by name.
     *
     * @param secretsManager optional secrets manager identifier for this request (may be null)
     */
    public RequestContext withRequest(IRPSValue[] rpsValues,
                                      String rightsContextName,
                                      String processingContextName,
                                      Context loggingContext,
                                      UUID secretsManager) throws Exception {
        if (engineContextResolver == null)
            throw new Exception("Context resolver not found");

        Context rightsContext = engineContextResolver.resolveRightContext(rightsContextName);
        Context processingContext = engineContextResolver.resolveProcessingContext(processingContextName);

        return withRequest(rpsValues, rightsContext, processingContext, loggingContext, secretsManager);
    }

    public RequestContext withRequest(IRPSValue[] rpsValues,
                                      Context rightsContext,
                                      Context processingContext,
                                      Context loggingContext) {
        return withRequest(rpsValues, rightsContext, processingContext, loggingContext, null);
    }

    /**
     * Adds a request to this context. Each request may use a different secrets manager.
     *
     * @param secretsManager optional secrets manager identifier for this request (may be null)
     */
    public RequestContext withRequest(IRPSValue[] rpsValues,
                                      Context rightsContext,
                                      Context processingContext,
                                      Context loggingContext,
                                      UUID secretsManager) {
        Request request = new Request();

        request.setValues(rpsValues);
        request.setRightContext(rightsContext);
        request.setProcessingContext(processingContext);
        request.setLoggingContext(loggingContext);
        request.setSecretsManager(secretsManager);

        requestsByGuid.put(request.getGuid(), request);

        return this;
    }

    public RequestContext withRequest(IRPSValue[] rpsValues,
                                      Context rightsContext,
                                      Context processingContext) {
        return withRequest(rpsValues, rightsContext, processingContext, (Context) null);
    }
}
