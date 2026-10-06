package ch.regdata.rps.engine.client.enginecontext;

import ch.regdata.rps.engine.client.Context;

public interface IRPSEngineContextProvider {

    void initialize();

    RightContext tryGetRightsContext(String contextKey);

    Context tryGetProcessingContext(String contextKey);
}
