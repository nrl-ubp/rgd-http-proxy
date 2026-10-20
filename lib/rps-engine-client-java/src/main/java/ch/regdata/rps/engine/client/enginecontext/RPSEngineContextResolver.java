package ch.regdata.rps.engine.client.enginecontext;


import ch.regdata.rps.engine.client.Context;

public class RPSEngineContextResolver {

    private final IRPSEngineContextProvider contextProvider;

    public RPSEngineContextResolver(IRPSEngineContextProvider contextProvider) {
        this.contextProvider = contextProvider;
    }

    public Context resolveRightContext(String rightsContextKey) throws Exception {
        RightContext context = contextProvider.tryGetRightsContext(rightsContextKey);
        if (context == null)
            throw new Exception("Rights context = " + rightsContextKey + "not found");
        return context;
    }

    public Context resolveProcessingContext(String processingContextKey) throws Exception {
        Context context = contextProvider.tryGetProcessingContext(processingContextKey);
        if (context == null)
            throw new Exception("Processing context = " + processingContextKey + "not found");
        return context;
    }
}
