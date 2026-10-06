package ch.regdata.rps.engine.client.enginecontext;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public abstract class RPSEngineContextProviderBase implements IRPSEngineContextProvider {

    private ConcurrentHashMap<String, RightContext> rightsContextsByKey;
    private ConcurrentHashMap<String, ProcessingContext> processingContextsByKey;

    public RightContext tryGetRightsContext(String contextKey) {
        if (rightsContextsByKey == null)
            throw new RuntimeException("Context provider is not initialized");

        return rightsContextsByKey.get(contextKey);
    }

    public ProcessingContext tryGetProcessingContext(String contextKey) {
        if (processingContextsByKey == null)
            throw new RuntimeException("Context provider is not initialized");

        return processingContextsByKey.get(contextKey);
    }

    public void initialize() {
        rightsContextsByKey = new ConcurrentHashMap<String, RightContext>(getRightsContexts());
        processingContextsByKey = new ConcurrentHashMap<String, ProcessingContext>(getProcessingContexts());
    }

    protected abstract HashMap<String, RightContext> getRightsContexts();

    protected abstract HashMap<String, ProcessingContext> getProcessingContexts();
}
